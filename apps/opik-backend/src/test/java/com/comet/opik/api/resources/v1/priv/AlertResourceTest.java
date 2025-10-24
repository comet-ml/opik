package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.api.AlertType;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.Project;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
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
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AlertResourceClient;
import com.comet.opik.api.resources.utils.resources.GuardrailsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.PromptResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.v1.events.webhooks.AlertEventEvaluationService;
import com.comet.opik.api.resources.v1.events.webhooks.pagerduty.PagerDutyWebhookPayload;
import com.comet.opik.api.resources.v1.events.webhooks.slack.SlackBlock;
import com.comet.opik.api.resources.v1.events.webhooks.slack.SlackWebhookPayload;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.GuardrailResult;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.function.TriConsumer;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hc.core5.http.HttpStatus;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.awaitility.Awaitility;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.AlertEventType.PROMPT_COMMITTED;
import static com.comet.opik.api.AlertEventType.PROMPT_CREATED;
import static com.comet.opik.api.AlertEventType.PROMPT_DELETED;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.traces.TraceAssertions.IGNORED_FIELDS_TRACES;
import static com.comet.opik.api.resources.v1.events.webhooks.WebhookHttpClient.BEARER_PREFIX;
import static com.comet.opik.api.resources.v1.events.webhooks.pagerduty.PagerDutyWebhookPayloadMapper.ROUTING_KEY_METADATA_KEY;
import static com.comet.opik.api.resources.v1.events.webhooks.slack.SlackWebhookPayloadMapper.BASE_URL_METADATA_KEY;
import static com.comet.opik.api.resources.v1.priv.PromptResourceTest.PROMPT_IGNORED_FIELDS;
import static com.comet.opik.infrastructure.EncryptionUtils.decrypt;
import static com.comet.opik.infrastructure.EncryptionUtils.maskApiKey;
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
            "lastUpdatedBy", "webhook.name", "webhook.secretToken", "webhook.createdAt", "webhook.lastUpdatedAt",
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
    private PromptResourceClient promptResourceClient;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private GuardrailsResourceClient guardrailsResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        String baseUrl = TestUtils.getBaseUrl(client);
        this.alertResourceClient = new AlertResourceClient(client);
        promptResourceClient = new PromptResourceClient(client, baseUrl, factory);
        projectResourceClient = new ProjectResourceClient(client, baseUrl, factory);
        traceResourceClient = new TraceResourceClient(client, baseUrl);
        guardrailsResourceClient = new GuardrailsResourceClient(client, baseUrl);

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
                            generateAlert().toBuilder().name(null).build(),
                            422,
                            new ErrorMessage(List.of("Alert name is required")),
                            ErrorMessage.class),
                    arguments(
                            generateAlert().toBuilder().name("").build(),
                            422,
                            new ErrorMessage(List.of("Alert name is required")),
                            ErrorMessage.class),
                    arguments(
                            generateAlert().toBuilder().name("   ").build(),
                            422,
                            new ErrorMessage(List.of("Alert name is required")),
                            ErrorMessage.class),
                    arguments(
                            generateAlert().toBuilder().name("a".repeat(256)).build(),
                            422,
                            new ErrorMessage(List.of("name size must be between 0 and 255")),
                            ErrorMessage.class),

                    // WebhookId validation
                    arguments(
                            generateAlert().toBuilder().webhook(null).build(),
                            422,
                            new ErrorMessage(List.of("webhook must not be null")),
                            ErrorMessage.class),

                    // Invalid UUID version
                    arguments(
                            generateAlert().toBuilder().id(UUID.randomUUID()).build(),
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

            compareAlerts(alert, actualAlert, true);
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

            compareAlerts(updatedAlert, actualUpdatedAlert, true);
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
                    null, true);
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
                    null, true);
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

            findAlertsAndAssertPage(alertPage1, mock.getLeft(), mock.getRight(), alerts.size(), 1, null, null, true);
            findAlertsAndAssertPage(alertPage2, mock.getLeft(), mock.getRight(), alerts.size(), 2, null, null, true);
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
                    List.of(sorting), null, false);
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
                            SortingField.builder().field(SortableFields.WEBHOOK_URL).direction(Direction.DESC)
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
                    1, null, List.of(filter), false);
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
                                    .value(alerts.getFirst().name().substring(0, 2))
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

                    // alertType field filtering
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.ALERT_TYPE)
                                    .operator(Operator.EQUAL)
                                    .value(alerts.getFirst().alertType().getValue())
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> alerts.stream()
                                    .filter(alert -> alert.alertType()
                                            .equals(alerts.getFirst().alertType()))
                                    .toList()),
                    Arguments.of(
                            (Function<List<Alert>, AlertFilter>) alerts -> AlertFilter.builder()
                                    .field(AlertField.ALERT_TYPE)
                                    .operator(Operator.NOT_EQUAL)
                                    .value(alerts.getFirst().alertType().getValue())
                                    .build(),
                            (Function<List<Alert>, List<Alert>>) alerts -> alerts.stream()
                                    .filter(alert -> !alert.alertType()
                                            .equals(alerts.getFirst().alertType()))
                                    .toList()),

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
            var alert1 = generateAlert();
            var alert2 = generateAlert();
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

        @ParameterizedTest
        @MethodSource
        @DisplayName("Success: should test webhook successfully when webhook server responds with 2xx")
        void testWebhook__whenWebhookServerRespondsWithSuccess__thenReturnSuccessResult(AlertEventType eventType,
                AlertType alertType) {
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
                    .alertType(alertType)
                    .triggers(List.of(alert.triggers().getFirst().toBuilder()
                            .eventType(eventType)
                            .build()))
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

        private static Stream<Arguments> testWebhook__whenWebhookServerRespondsWithSuccess__thenReturnSuccessResult() {
            return Stream.of(AlertEventType.values())
                    .flatMap(eventType -> Stream.of(AlertType.values())
                            .map(alertType -> Arguments.of(eventType, alertType)));
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

        private void assertWebhookTestResultRequest(Alert alert, String requestBodyJson) {
            try {
                // Deserialize the JSON string based on alert type
                if (alert.alertType() == AlertType.GENERAL) {
                    // For GENERAL alerts, verify the full webhook event structure
                    WebhookEvent<Map<String, Object>> actualEvent = (WebhookEvent<Map<String, Object>>) JsonUtils
                            .readValue(requestBodyJson, WebhookEvent.class);

                    // Verify the webhook event is not null
                    assertThat(actualEvent).isNotNull();

                    // Verify event metadata
                    assertThat(actualEvent.getId()).isNotNull();
                    assertThat(actualEvent.getAlertId()).isEqualTo(alert.id());
                    assertThat(actualEvent.getCreatedAt()).isNotNull();
                    assertThat(actualEvent.getMaxRetries()).isEqualTo(1);

                    assertThat(actualEvent.getEventType()).isEqualTo(alert.triggers().getFirst().eventType());

                    // Verify payload
                    Map<String, Object> payload = actualEvent.getPayload();

                    // Verify payload fields
                    assertThat(payload.get("alertId")).isEqualTo(alert.id().toString());
                    assertThat(payload.get("alertName")).isEqualTo(alert.name());
                    assertThat(payload.get("eventType")).isEqualTo(alert.triggers().getFirst().eventType().getValue());
                    assertThat(payload.get("aggregationType")).isEqualTo("consolidated");

                    // Verify eventIds
                    @SuppressWarnings("unchecked")
                    var eventIds = (Collection<String>) payload.get("eventIds");
                    assertThat(eventIds).hasSize(1);

                    // Verify eventCount
                    assertThat(payload.get("eventCount")).isEqualTo(1);

                    // Verify userNames
                    @SuppressWarnings("unchecked")
                    var userNames = (Collection<String>) payload.get("userNames");
                    assertThat(userNames).isNotNull();
                    assertThat(userNames).hasSize(1);
                    assertThat(userNames).contains("test-user");

                    // Verify metadata
                    var metadata = (Collection<?>) payload.get("metadata");
                    assertThat(metadata).isNotNull();
                    assertThat(metadata).hasSize(1);

                    // Verify message format
                    assertThat(payload.get("message").toString())
                            .isEqualTo(String.format("Alert '%s': %d %s events aggregated",
                                    alert.name(), eventIds.size(), alert.triggers().getFirst().eventType().getValue()));
                } else if (alert.alertType() == AlertType.SLACK) {
                    // For SLACK just verify it's valid JSON
                    var slackPayload = JsonUtils.readValue(requestBodyJson, SlackWebhookPayload.class);
                    assertThat(slackPayload).isNotNull();
                } else if (alert.alertType() == AlertType.PAGERDUTY) {
                    // For PAGERDUTY just verify it's valid JSON
                    var pagerDutyPayload = JsonUtils.readValue(requestBodyJson, PagerDutyWebhookPayload.class);
                    assertThat(pagerDutyPayload).isNotNull();
                }

            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize webhook request body", e);
            }
        }
    }

    @Nested
    @DisplayName("Test Alert Events:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class TestAlertEvents {

        private WireMockServer externalWebhookServer;
        private static final String WEBHOOK_PATH = "/webhook";
        private String webhookUrl;

        private static final String[] IGNORED_FIELDS_FEEDBACK_SCORES_BATCH_ITEM = {"id", "projectId", "projectName",
                "createdAt",
                "lastUpdatedAt", "createdBy", "lastUpdatedBy", "author"};

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

            // Setup WireMock to return successful response
            externalWebhookServer.stubFor(post(urlEqualTo(WEBHOOK_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader(HttpHeader.CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON)
                            .withBody("{\"status\":\"success\"}")));

            webhookUrl = "http://localhost:" + externalWebhookServer.port() + WEBHOOK_PATH;
        }

        private Alert createAlertForEvent(AlertTrigger alertTrigger) {
            var alert = generateAlert();
            var webhook = alert.webhook().toBuilder()
                    .url(webhookUrl)
                    .build();
            return alert.toBuilder()
                    .webhook(webhook)
                    .alertType(AlertType.GENERAL)
                    .triggers(List.of(alertTrigger))
                    .enabled(true)
                    .build();
        }

        @Test
        @DisplayName("Success: should successfully send prompt creation event to webhook")
        void testCreatePromptEvent__whenWebhookServerReceivesAlert() {
            // Given
            var mock = prepareMockWorkspace();

            // Create alert with webhook
            var alert = createAlertForEvent(AlertTrigger.builder()
                    .eventType(PROMPT_CREATED)
                    .build());

            // First create an alert for the event
            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);

            // Create a prompt to trigger the event
            var expectedPrompt = factory.manufacturePojo(Prompt.class)
                    .toBuilder()
                    .versionCount(0L)
                    .createdBy(USER)
                    .lastUpdatedBy(USER)
                    .build();
            promptResourceClient.createPrompt(expectedPrompt, mock.getLeft(), mock.getRight());

            var payload = verifyWebhookCalledAndGetPayload(alert);
            Prompt prompt = JsonUtils.readValue(payload, Prompt.class);

            assertThat(prompt)
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withIgnoredFields(PROMPT_IGNORED_FIELDS)
                                    .withComparatorForType(
                                            PromptResourceTest::comparatorForCreateAtAndUpdatedAt,
                                            Instant.class)
                                    .build())
                    .isEqualTo(expectedPrompt);
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("Success: should successfully send prompt deletion event to webhook")
        void testDeletePromptEvent__whenWebhookServerReceivesAlert(TriConsumer<UUID, String, String> deleteAction) {
            // Given
            var mock = prepareMockWorkspace();

            // Create alert with webhook
            var alert = createAlertForEvent(AlertTrigger.builder()
                    .eventType(PROMPT_DELETED)
                    .build());

            // First create an alert for the event
            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);

            // Create a prompt to trigger the event
            var prompt = factory.manufacturePojo(Prompt.class)
                    .toBuilder()
                    .versionCount(0L)
                    .createdBy(USER)
                    .lastUpdatedBy(USER)
                    .build();
            var promptId = promptResourceClient.createPrompt(prompt, mock.getLeft(), mock.getRight());
            var actualPrompt = promptResourceClient.getPrompt(promptId, mock.getLeft(), mock.getRight());

            // Now delete the prompt to trigger the deletion event
            deleteAction.accept(promptId, mock.getRight(), mock.getLeft());

            var payload = verifyWebhookCalledAndGetPayload(alert);
            List<Prompt> prompts = JsonUtils.readCollectionValue(payload, List.class, Prompt.class);

            assertThat(prompts).hasSize(1);
            assertThat(prompts.getFirst())
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withIgnoredFields(PROMPT_IGNORED_FIELDS)
                                    .withComparatorForType(
                                            PromptResourceTest::comparatorForCreateAtAndUpdatedAt,
                                            Instant.class)
                                    .build())
                    .isEqualTo(actualPrompt);
        }

        Stream<Arguments> testDeletePromptEvent__whenWebhookServerReceivesAlert() {
            return Stream.of(
                    Arguments.of(
                            (TriConsumer<UUID, String, String>) (promptId, workspace, apiKey) -> promptResourceClient
                                    .deletePrompt(promptId, apiKey, workspace)),
                    Arguments.of(
                            (TriConsumer<UUID, String, String>) (promptId, workspace, apiKey) -> promptResourceClient
                                    .deletePromptBatch(Set.of(promptId), apiKey, workspace)));
        }

        @Test
        @DisplayName("Success: should successfully send prompt commit event to webhook")
        void testPromptCommitEvent__whenWebhookServerReceivesAlert() {
            // Given
            var mock = prepareMockWorkspace();

            // Create alert with webhook
            var alert = createAlertForEvent(AlertTrigger.builder()
                    .eventType(PROMPT_COMMITTED)
                    .build());

            // First create an alert for the event
            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);

            // Create a prompt and commit to trigger the event
            var expectedPrompt = factory.manufacturePojo(Prompt.class);
            var expectedPromptVersion = promptResourceClient.createPromptVersion(expectedPrompt, mock.getLeft(),
                    mock.getRight());

            var payload = verifyWebhookCalledAndGetPayload(alert);
            PromptVersion promptVersion = JsonUtils.readValue(payload, PromptVersion.class);

            assertThat(promptVersion)
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withComparatorForType(
                                            PromptResourceTest::comparatorForCreateAtAndUpdatedAt,
                                            Instant.class)
                                    .build())
                    .isEqualTo(expectedPromptVersion);
        }

        @ParameterizedTest
        @MethodSource("traceFeedbackScoreProjectScopeProvider")
        @DisplayName("when single trace feedback score is created, then webhook is called based on project scope")
        void whenSingleTraceFeedbackScoreIsCreated_thenWebhookIsCalledBasedOnProjectScope(
                Function<UUID, AlertTrigger> getAlertTrigger) {
            var mock = prepareMockWorkspace();

            // Create a project
            String projectName = RandomStringUtils.randomAlphabetic(10);
            UUID projectId = projectResourceClient.createProject(projectName, mock.getLeft(), mock.getRight());

            // Create an alert with or without project scope configuration
            var alert = createAlertForEvent(getAlertTrigger.apply(projectId));
            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);

            // Create a trace
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .build();
            traceResourceClient.createTrace(trace, mock.getLeft(), mock.getRight());

            // Create a feedback score
            FeedbackScore feedbackScore = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                    .source(ScoreSource.SDK)
                    .build();
            traceResourceClient.feedbackScore(trace.id(), feedbackScore, mock.getRight(), mock.getLeft());

            // Wait for webhook call and verify
            var payload = verifyWebhookCalledAndGetPayload(alert);
            List<FeedbackScoreBatchItem> feedbackScores = JsonUtils.readCollectionValue(
                    payload, List.class, FeedbackScoreBatchItem.class);

            assertThat(feedbackScores).hasSize(1);
            FeedbackScoreBatchItem actualScore = feedbackScores.getFirst();

            // Assert feedback score details using recursive comparison
            assertThat(actualScore)
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withComparatorForType(Comparator.naturalOrder(), Instant.class)
                                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                                    .withIgnoredFields(IGNORED_FIELDS_FEEDBACK_SCORES_BATCH_ITEM)
                                    .build())
                    .isEqualTo(feedbackScore);

            assertThat(actualScore.id()).isEqualTo(trace.id());
            assertThat(actualScore.author()).isEqualTo(USER);
        }

        static Stream<Arguments> traceFeedbackScoreProjectScopeProvider() {
            return Stream.of(
                    Arguments.of((Function<UUID, AlertTrigger>) projectId -> AlertTrigger.builder()
                            .eventType(AlertEventType.TRACE_FEEDBACK_SCORE)
                            .build()),
                    Arguments.of((Function<UUID, AlertTrigger>) projectId -> AlertTrigger.builder()
                            .eventType(AlertEventType.TRACE_FEEDBACK_SCORE)
                            .triggerConfigs(List.of(
                                    AlertTriggerConfig.builder()
                                            .type(AlertTriggerConfigType.SCOPE_PROJECT)
                                            .configValue(Map.of(
                                                    AlertEventEvaluationService.PROJECT_SCOPE_CONFIG_KEY,
                                                    JsonUtils.writeValueAsString(Set.of(projectId))))
                                            .build()))
                            .build()));
        }

        @Test
        @DisplayName("when batch of trace feedback scores is created, then webhook is called")
        void whenBatchOfTraceFeedbackScoresIsCreated_thenWebhookIsCalled() {
            var mock = prepareMockWorkspace();

            // Create a project
            String projectName = RandomStringUtils.randomAlphabetic(10);
            projectResourceClient.createProject(projectName, mock.getLeft(), mock.getRight());

            // Create an alert for feedback score events
            var alertTrigger = AlertTrigger.builder()
                    .eventType(AlertEventType.TRACE_FEEDBACK_SCORE)
                    .build();
            var alert = createAlertForEvent(alertTrigger);
            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);

            // Create a trace
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .build();
            traceResourceClient.createTrace(trace, mock.getLeft(), mock.getRight());

            // Create a batch of feedback scores
            List<FeedbackScoreBatchItem> feedbackScores = PodamFactoryUtils
                    .manufacturePojoList(factory, FeedbackScoreBatchItem.class)
                    .stream()
                    .map(item -> (FeedbackScoreBatchItem) item.toBuilder()
                            .source(ScoreSource.SDK)
                            .id(trace.id())
                            .projectName(projectName)
                            .build())
                    .toList();

            traceResourceClient.feedbackScores(feedbackScores, mock.getLeft(), mock.getRight());

            // Wait for webhook call and verify
            var payload = verifyWebhookCalledAndGetPayload(alert);
            List<FeedbackScoreBatchItem> actualFeedbackScores = JsonUtils.readCollectionValue(
                    payload, List.class, FeedbackScoreBatchItem.class);

            assertThat(actualFeedbackScores).hasSize(feedbackScores.size());

            // Assert feedback score details using recursive comparison
            assertThat(actualFeedbackScores)
                    .usingRecursiveComparison()
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .ignoringFields(IGNORED_FIELDS_FEEDBACK_SCORES_BATCH_ITEM)
                    .ignoringCollectionOrder()
                    .isEqualTo(feedbackScores);

            actualFeedbackScores.forEach(actualScore -> {
                assertThat(actualScore.id()).isEqualTo(trace.id());
                assertThat(actualScore.author()).isEqualTo(USER);
            });
        }

        @ParameterizedTest
        @MethodSource("traceThreadFeedbackScoreProjectScopeProvider")
        @DisplayName("when single trace thread feedback score is created, then webhook is called based on project scope")
        void whenSingleTraceThreadFeedbackScoreIsCreated_thenWebhookIsCalledBasedOnProjectScope(
                Function<UUID, AlertTrigger> getAlertTrigger) {
            var mock = prepareMockWorkspace();

            // Create a project
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, mock.getLeft(), mock.getRight());

            // Create an alert with or without project scope configuration
            var alert = createAlertForEvent(getAlertTrigger.apply(projectId));
            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);

            // Create a thread with multiple traces
            var threadId = UUID.randomUUID().toString();
            var traces = IntStream.range(0, 3)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .threadId(threadId)
                            .projectName(project.name())
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(traces, mock.getLeft(), mock.getRight());

            // Wait for the thread to be created
            Awaitility.await().untilAsserted(() -> {
                var thread = traceResourceClient.getTraceThread(threadId, projectId, mock.getLeft(), mock.getRight());
                assertThat(thread.threadModelId()).isNotNull();
            });

            // Get the thread
            var thread = traceResourceClient.getTraceThread(threadId, projectId, mock.getLeft(), mock.getRight());

            // Close thread
            traceResourceClient.closeTraceThread(thread.id(), projectId, project.name(), mock.getLeft(),
                    mock.getRight());

            // Create a feedback score for the thread
            FeedbackScore feedbackScore = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                    .source(ScoreSource.SDK)
                    .build();

            var feedbackScoreBatchItem = FeedbackScoreBatchItemThread.builder()
                    .threadId(thread.id())
                    .projectName(project.name())
                    .name(feedbackScore.name())
                    .value(feedbackScore.value())
                    .reason(feedbackScore.reason())
                    .source(feedbackScore.source())
                    .build();

            traceResourceClient.threadFeedbackScores(List.of(feedbackScoreBatchItem), mock.getLeft(), mock.getRight());

            // Wait for webhook call and verify
            var payload = verifyWebhookCalledAndGetPayload(alert);
            List<FeedbackScoreBatchItemThread> feedbackScores = JsonUtils.readCollectionValue(
                    payload, List.class, FeedbackScoreBatchItemThread.class);

            assertThat(feedbackScores).hasSize(1);
            FeedbackScoreBatchItemThread actualScore = feedbackScores.getFirst();

            // Assert feedback score details using recursive comparison
            assertThat(actualScore)
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withComparatorForType(Comparator.naturalOrder(), Instant.class)
                                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                                    .withIgnoredFields(IGNORED_FIELDS_FEEDBACK_SCORES_BATCH_ITEM)
                                    .build())
                    .isEqualTo(feedbackScoreBatchItem);

            assertThat(actualScore.threadId()).isEqualTo(thread.id());
            assertThat(actualScore.author()).isEqualTo(USER);
        }

        static Stream<Arguments> traceThreadFeedbackScoreProjectScopeProvider() {
            return Stream.of(
                    Arguments.of((Function<UUID, AlertTrigger>) projectId -> AlertTrigger.builder()
                            .eventType(AlertEventType.TRACE_THREAD_FEEDBACK_SCORE)
                            .build()),
                    Arguments.of((Function<UUID, AlertTrigger>) projectId -> AlertTrigger.builder()
                            .eventType(AlertEventType.TRACE_THREAD_FEEDBACK_SCORE)
                            .triggerConfigs(List.of(
                                    AlertTriggerConfig.builder()
                                            .type(AlertTriggerConfigType.SCOPE_PROJECT)
                                            .configValue(Map.of(
                                                    AlertEventEvaluationService.PROJECT_SCOPE_CONFIG_KEY,
                                                    JsonUtils.writeValueAsString(Set.of(projectId))))
                                            .build()))
                            .build()));
        }

        @ParameterizedTest
        @MethodSource("traceErrorsProjectScopeProvider")
        @DisplayName("when single trace with error is created, then webhook is called based on project scope")
        void whenSingleTraceWithErrorIsCreated_thenWebhookIsCalledBasedOnProjectScope(
                Function<UUID, AlertTrigger> getAlertTrigger) {
            var mock = prepareMockWorkspace();

            // Create a project
            String projectName = RandomStringUtils.randomAlphabetic(10);
            UUID projectId = projectResourceClient.createProject(projectName, mock.getLeft(), mock.getRight());

            // Create an alert with or without project scope configuration
            var alert = createAlertForEvent(getAlertTrigger.apply(projectId));
            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);

            // Create a trace with error
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .usage(null)
                    .visibilityMode(null)
                    .build();
            traceResourceClient.createTrace(trace, mock.getLeft(), mock.getRight());

            // Wait for webhook call and verify
            var payload = verifyWebhookCalledAndGetPayload(alert);
            List<Trace> traces = JsonUtils.readCollectionValue(payload, List.class, Trace.class);

            assertThat(traces).hasSize(1);
            Trace actualTrace = traces.getFirst();

            assertThat(actualTrace)
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withIgnoredFields(IGNORED_FIELDS_TRACES)
                                    .build())
                    .isEqualTo(trace);

            assertThat(actualTrace.projectName()).isEqualTo(projectName);
            assertThat(actualTrace.projectId()).isEqualTo(projectId);
        }

        static Stream<Arguments> traceErrorsProjectScopeProvider() {
            return Stream.of(
                    Arguments.of((Function<UUID, AlertTrigger>) projectId -> AlertTrigger.builder()
                            .eventType(AlertEventType.TRACE_ERRORS)
                            .build()),
                    Arguments.of((Function<UUID, AlertTrigger>) projectId -> AlertTrigger.builder()
                            .eventType(AlertEventType.TRACE_ERRORS)
                            .triggerConfigs(List.of(
                                    AlertTriggerConfig.builder()
                                            .type(AlertTriggerConfigType.SCOPE_PROJECT)
                                            .configValue(Map.of(
                                                    AlertEventEvaluationService.PROJECT_SCOPE_CONFIG_KEY,
                                                    JsonUtils.writeValueAsString(Set.of(projectId))))
                                            .build()))
                            .build()));
        }

        @Test
        @DisplayName("when batch of traces with errors is created, then webhook is called")
        void whenBatchOfTracesWithErrorsIsCreated_thenWebhookIsCalled() {
            var mock = prepareMockWorkspace();

            // Create a project
            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, mock.getLeft(), mock.getRight());

            // Create an alert for trace errors
            var alertTrigger = AlertTrigger.builder()
                    .eventType(AlertEventType.TRACE_ERRORS)
                    .build();
            var alert = createAlertForEvent(alertTrigger);
            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);

            // Create a batch of traces with errors
            List<Trace> tracesWithErrors = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .visibilityMode(null)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(tracesWithErrors, mock.getLeft(), mock.getRight());

            // Wait for webhook call and verify
            var payload = verifyWebhookCalledAndGetPayload(alert);
            List<Trace> actualTraces = JsonUtils.readCollectionValue(payload, List.class, Trace.class);

            assertThat(actualTraces).hasSize(tracesWithErrors.size());

            actualTraces.forEach(actualTrace -> {
                assertThat(actualTrace.projectName()).isEqualTo(projectName);
                assertThat(actualTrace.projectId()).isEqualTo(projectId);
            });

            assertThat(actualTraces)
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withIgnoredFields(IGNORED_FIELDS_TRACES)
                                    .build())
                    .ignoringCollectionOrder()
                    .isEqualTo(tracesWithErrors);
        }

        @ParameterizedTest
        @MethodSource("traceGuardrailsTriggeredProjectScopeProvider")
        @DisplayName("when guardrails are triggered for a trace, then webhook is called based on project scope")
        void whenGuardrailsAreTriggeredForTrace_thenWebhookIsCalledBasedOnProjectScope(
                Function<UUID, AlertTrigger> getAlertTrigger) {
            var mock = prepareMockWorkspace();

            // Create a project
            String projectName = RandomStringUtils.randomAlphabetic(10);
            UUID projectId = projectResourceClient.createProject(projectName, mock.getLeft(), mock.getRight());

            // Create an alert with or without project scope configuration
            var alert = createAlertForEvent(getAlertTrigger.apply(projectId));
            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);

            // Create a trace
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .usage(null)
                    .visibilityMode(null)
                    .build();
            traceResourceClient.createTrace(trace, mock.getLeft(), mock.getRight());

            // Create guardrails for the trace
            Guardrail guardrail = factory.manufacturePojo(Guardrail.class).toBuilder()
                    .entityId(trace.id())
                    .secondaryId(UUID.randomUUID())
                    .projectName(projectName)
                    .result(GuardrailResult.FAILED)
                    .build();
            guardrailsResourceClient.addBatch(List.of(guardrail), mock.getLeft(), mock.getRight());

            // Wait for webhook call and verify
            var payload = verifyWebhookCalledAndGetPayload(alert);
            List<Guardrail> guardrails = JsonUtils.readCollectionValue(payload, List.class, Guardrail.class);

            assertThat(guardrails).hasSize(1);
            Guardrail actualGuardrail = guardrails.getFirst();

            // Assert guardrail details using recursive comparison
            assertThat(actualGuardrail)
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withIgnoredFields("id", "projectId")
                                    .build())
                    .isEqualTo(guardrail);
        }

        static Stream<Arguments> traceGuardrailsTriggeredProjectScopeProvider() {
            return Stream.of(
                    Arguments.of((Function<UUID, AlertTrigger>) projectId -> AlertTrigger.builder()
                            .eventType(AlertEventType.TRACE_GUARDRAILS_TRIGGERED)
                            .build()),
                    Arguments.of((Function<UUID, AlertTrigger>) projectId -> AlertTrigger.builder()
                            .eventType(AlertEventType.TRACE_GUARDRAILS_TRIGGERED)
                            .triggerConfigs(List.of(
                                    AlertTriggerConfig.builder()
                                            .type(AlertTriggerConfigType.SCOPE_PROJECT)
                                            .configValue(Map.of(
                                                    AlertEventEvaluationService.PROJECT_SCOPE_CONFIG_KEY,
                                                    JsonUtils.writeValueAsString(Set.of(projectId))))
                                            .build()))
                            .build()));
        }

        private String verifyWebhookCalledAndGetPayload(Alert alert) {
            // Wait for the webhook event to be sent
            Awaitility.await().untilAsserted(() -> {
                var requests = externalWebhookServer.findAll(postRequestedFor(urlEqualTo(WEBHOOK_PATH)));
                assertThat(requests).hasSize(1);
            });

            var actualRequest = externalWebhookServer.findAll(postRequestedFor(urlEqualTo(WEBHOOK_PATH))).get(0);

            String actualRequestBody = actualRequest.getBodyAsString();

            assertThat(actualRequest.header(HttpHeaders.AUTHORIZATION).firstValue())
                    .isEqualTo(BEARER_PREFIX + alert.webhook().secretToken());

            // Get sent event and verify it's payload
            WebhookEvent<Map<String, Object>> actualEvent = JsonUtils.readValue(actualRequestBody, WebhookEvent.class);
            List<Object> payloads = (List<Object>) actualEvent.getPayload().get("metadata");
            assertThat(payloads).hasSize(1);

            return JsonUtils.writeValueAsString(payloads.getFirst());
        }
    }

    @Nested
    @DisplayName("Test Alert Events for Native Integrations:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class TestAlertEventsForNativeIntegrations {

        private WireMockServer externalWebhookServer;
        private static final String WEBHOOK_PATH = "/webhook";
        private String webhookUrl;
        private static final String ALERT_NAME = "Test Webhook Alert";
        private static final String BASE_URL = "http://localhost:5555";
        private static final String TEST_ROUTING_KEY = "routingKeyForTest";

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

            // Setup WireMock to return successful response
            externalWebhookServer.stubFor(post(urlEqualTo(WEBHOOK_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader(HttpHeader.CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON)
                            .withBody("{\"ok\":true}")));

            webhookUrl = "http://localhost:" + externalWebhookServer.port() + WEBHOOK_PATH;
        }

        private Stream<Arguments> alertTypeProvider() {
            return Stream.of(
                    Arguments.of(AlertType.SLACK),
                    Arguments.of(AlertType.PAGERDUTY));
        }

        private Alert createAlertForEvent(AlertTrigger alertTrigger, AlertType alertType) {
            var alert = generateAlert();
            var webhook = alert.webhook().toBuilder()
                    .url(webhookUrl)
                    .build();
            return alert.toBuilder()
                    .name(ALERT_NAME)
                    .webhook(webhook)
                    .alertType(alertType)
                    .triggers(List.of(alertTrigger))
                    .enabled(true)
                    .metadata(Map.of(
                            BASE_URL_METADATA_KEY, BASE_URL,
                            ROUTING_KEY_METADATA_KEY, TEST_ROUTING_KEY))
                    .build();
        }

        private <T> T verifyWebhookCalledAndGetPayload(Class<T> payloadClass) {
            // Wait for the webhook event to be sent
            Awaitility.await().untilAsserted(() -> {
                var requests = externalWebhookServer.findAll(postRequestedFor(urlEqualTo(WEBHOOK_PATH)));
                assertThat(requests).hasSize(1);
            });

            var actualRequest = externalWebhookServer.findAll(postRequestedFor(urlEqualTo(WEBHOOK_PATH))).get(0);
            String actualRequestBody = actualRequest.getBodyAsString();

            return JsonUtils.readValue(actualRequestBody, payloadClass);
        }

        private void verifyPayload(AlertType alertType, int expectedEventCount,
                String expectedEventType, List<String> expectedDetailsContains) {
            switch (alertType) {
                case SLACK -> {
                    var slackPayload = verifyWebhookCalledAndGetPayload(SlackWebhookPayload.class);
                    verifySlackBlockStructure(slackPayload, expectedEventCount, expectedEventType,
                            expectedDetailsContains);
                }
                case PAGERDUTY -> {
                    var pagerDutyPayload = verifyWebhookCalledAndGetPayload(PagerDutyWebhookPayload.class);
                    verifyPagerDutyPayloadStructure(pagerDutyPayload);
                }
                default ->
                    throw new IllegalArgumentException("Unsupported alert type for alerts integration: " + alertType);
            }
        }

        private void verifySlackBlockStructure(SlackWebhookPayload slackPayload, int expectedEventCount,
                String expectedEventType, List<String> expectedDetailsContains) {
            // Verify blocks structure (3 blocks: header, summary, details)
            assertThat(slackPayload.blocks()).isNotNull();
            assertThat(slackPayload.blocks()).hasSize(3);

            // Verify header block
            SlackBlock headerBlock = slackPayload.blocks().get(0);
            assertThat(headerBlock.type()).isEqualTo("header");
            assertThat(headerBlock.text()).isNotNull();
            assertThat(headerBlock.text().type()).isEqualTo("plain_text");
            assertThat(headerBlock.text().text()).isEqualTo(ALERT_NAME);

            // Verify summary block
            SlackBlock summaryBlock = slackPayload.blocks().get(1);
            assertThat(summaryBlock.type()).isEqualTo("section");
            assertThat(summaryBlock.text()).isNotNull();
            assertThat(summaryBlock.text().type()).isEqualTo("mrkdwn");
            String summaryContent = summaryBlock.text().text();
            assertThat(summaryContent).contains("*" + expectedEventCount + "*");
            assertThat(summaryContent).contains(expectedEventType);

            // Verify details block (mainText)
            SlackBlock detailsBlock = slackPayload.blocks().get(2);
            assertThat(detailsBlock.type()).isEqualTo("section");
            assertThat(detailsBlock.text()).isNotNull();
            assertThat(detailsBlock.text().type()).isEqualTo("mrkdwn");
            String details = detailsBlock.text().text();
            assertThat(details).isNotNull();

            // Verify expected strings in details
            for (String expectedString : expectedDetailsContains) {
                assertThat(details).contains(expectedString);
            }
        }

        private void verifySlackBlockStructureWithFallback(SlackWebhookPayload slackPayload, String fallbackText) {
            // Verify last 4th block, structure (4 blocks: header, summary, details, fallback)
            assertThat(slackPayload.blocks()).isNotNull();
            assertThat(slackPayload.blocks()).hasSize(4);

            // Verify details block (truncated mainText)
            SlackBlock detailsBlock = slackPayload.blocks().get(2);
            assertThat(detailsBlock.type()).isEqualTo("section");
            assertThat(detailsBlock.text()).isNotNull();
            assertThat(detailsBlock.text().type()).isEqualTo("mrkdwn");
            String details = detailsBlock.text().text();
            assertThat(details).isNotNull();
            assertThat(details.length()).isLessThanOrEqualTo(3000);

            // Verify fallback block (when text was truncated)
            SlackBlock fallbackBlock = slackPayload.blocks().get(3);
            assertThat(fallbackBlock.type()).isEqualTo("section");
            assertThat(fallbackBlock.text()).isNotNull();
            assertThat(fallbackBlock.text().type()).isEqualTo("mrkdwn");
            assertThat(fallbackBlock.text().text()).isEqualTo(fallbackText);
        }

        private void verifyPagerDutyPayloadStructure(PagerDutyWebhookPayload pagerDutyPayload) {
            // Verify payload structure
            assertThat(pagerDutyPayload).isNotNull();
            assertThat(pagerDutyPayload.routingKey()).isEqualTo(TEST_ROUTING_KEY);
            assertThat(pagerDutyPayload.eventAction()).isEqualTo("trigger");
            assertThat(pagerDutyPayload.dedupKey()).isNotNull();

            // Verify payload content
            assertThat(pagerDutyPayload.payload()).isNotNull();
            assertThat(pagerDutyPayload.payload().summary()).isEqualTo(ALERT_NAME);
            assertThat(pagerDutyPayload.payload().source()).isEqualTo("Opik");
            assertThat(pagerDutyPayload.payload().severity()).isNotNull();
            assertThat(pagerDutyPayload.payload().timestamp()).isNotNull();
            assertThat(pagerDutyPayload.payload().customDetails()).isNotNull();
        }

        @ParameterizedTest
        @MethodSource("alertTypeProvider")
        @DisplayName("Success: should send webhook formatted prompt creation event")
        void testPromptCreatedEvent(AlertType alertType) {
            // Given
            var mock = prepareMockWorkspace();

            // Create alert with webhook
            var alert = createAlertForEvent(AlertTrigger.builder()
                    .eventType(PROMPT_CREATED)
                    .build(), alertType);

            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            // Create a prompt to trigger the event
            var prompt = factory.manufacturePojo(Prompt.class)
                    .toBuilder()
                    .versionCount(0L)
                    .createdBy(USER)
                    .lastUpdatedBy(USER)
                    .build();
            var promptId = promptResourceClient.createPrompt(prompt, mock.getLeft(), mock.getRight());

            // Construct expected URL
            String expectedUrl = String.format(BASE_URL + "/%s/prompts/%s", mock.getRight(), promptId);

            // Verify webhook payload based on alert type
            verifyPayload(alertType, 1, "Prompt Created",
                    List.of("*Prompts Created:*\n", expectedUrl));
        }

        @ParameterizedTest
        @MethodSource("alertTypeProvider")
        @DisplayName("Success: should send webhook formatted prompt deletion event")
        void testPromptDeletedEvent(AlertType alertType) {
            // Given
            var mock = prepareMockWorkspace();

            // Create alert with webhook
            var alert = createAlertForEvent(AlertTrigger.builder()
                    .eventType(PROMPT_DELETED)
                    .build(), alertType);

            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            // Create and delete prompts to trigger the event
            var prompt1 = factory.manufacturePojo(Prompt.class)
                    .toBuilder()
                    .versionCount(0L)
                    .createdBy(USER)
                    .lastUpdatedBy(USER)
                    .build();
            var promptId1 = promptResourceClient.createPrompt(prompt1, mock.getLeft(), mock.getRight());

            var prompt2 = factory.manufacturePojo(Prompt.class)
                    .toBuilder()
                    .versionCount(0L)
                    .createdBy(USER)
                    .lastUpdatedBy(USER)
                    .build();
            var promptId2 = promptResourceClient.createPrompt(prompt2, mock.getLeft(), mock.getRight());

            promptResourceClient.deletePromptBatch(Set.of(promptId1, promptId2), mock.getLeft(), mock.getRight());

            // Verify webhook payload based on alert type
            verifyPayload(alertType, 1, "Prompt Deleted",
                    List.of("*Deleted Prompt IDs:*", promptId1.toString(), promptId2.toString()));
        }

        @ParameterizedTest
        @MethodSource("alertTypeProvider")
        @DisplayName("Success: should send webhook formatted prompt commit event")
        void testPromptCommittedEvent(AlertType alertType) {
            // Given
            var mock = prepareMockWorkspace();

            // Create alert with webhook
            var alert = createAlertForEvent(AlertTrigger.builder()
                    .eventType(PROMPT_COMMITTED)
                    .build(), alertType);

            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            // Create a prompt and commit to trigger the event
            var expectedPrompt = factory.manufacturePojo(Prompt.class);
            var expectedPromptVersion = promptResourceClient.createPromptVersion(expectedPrompt, mock.getLeft(),
                    mock.getRight());

            // Construct expected URL
            String expectedUrl = String.format(BASE_URL + "/%s/prompts/%s?activeVersionId=%s",
                    mock.getRight(), expectedPromptVersion.promptId(), expectedPromptVersion.id());

            // Verify webhook payload based on alert type
            verifyPayload(alertType, 1, "Prompt Committed",
                    List.of("*Prompts Committed:*\n", expectedUrl));
        }

        @ParameterizedTest
        @MethodSource("alertTypeProvider")
        @DisplayName("Success: should send webhook formatted trace errors event")
        void testTraceErrorsEvent(AlertType alertType) {
            // Given
            var mock = prepareMockWorkspace();

            // Create a project
            String projectName = RandomStringUtils.randomAlphabetic(10);
            UUID projectId = projectResourceClient.createProject(projectName, mock.getLeft(), mock.getRight());

            // Create alert with webhook
            var alert = createAlertForEvent(AlertTrigger.builder()
                    .eventType(AlertEventType.TRACE_ERRORS)
                    .build(), alertType);

            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            // Create traces with errors
            List<Trace> tracesWithErrors = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .limit(3)
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .visibilityMode(null)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(tracesWithErrors, mock.getLeft(), mock.getRight());

            // Construct expected URLs
            var expectedDetails = new ArrayList<String>();
            expectedDetails.add("*Traces with Errors:*\n");
            tracesWithErrors.forEach(trace -> {
                String expectedUrl = String.format(BASE_URL + "/%s/projects/%s/traces?trace=%s",
                        mock.getRight(), projectId, trace.id());
                expectedDetails.add(expectedUrl);
            });
            verifyPayload(alertType, 1, "Trace Errors", expectedDetails);
        }

        @ParameterizedTest
        @MethodSource("alertTypeProvider")
        @DisplayName("Success: should send webhook formatted trace feedback score event")
        void testTraceFeedbackScoreEvent(AlertType alertType) {
            // Given
            var mock = prepareMockWorkspace();

            // Create a project
            String projectName = RandomStringUtils.randomAlphabetic(10);
            UUID projectId = projectResourceClient.createProject(projectName, mock.getLeft(), mock.getRight());

            // Create alert with webhook
            var alert = createAlertForEvent(AlertTrigger.builder()
                    .eventType(AlertEventType.TRACE_FEEDBACK_SCORE)
                    .build(), alertType);

            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            // Create a trace
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .build();
            traceResourceClient.createTrace(trace, mock.getLeft(), mock.getRight());

            // Create a feedback score
            FeedbackScore feedbackScore = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                    .source(ScoreSource.SDK)
                    .build();
            traceResourceClient.feedbackScore(trace.id(), feedbackScore, mock.getRight(), mock.getLeft());

            // Construct expected URL
            String expectedUrl = String.format(
                    BASE_URL + "/%s/projects/%s/traces?trace=%s&traceTab=feedback_scores",
                    mock.getRight(), projectId, trace.id());

            // Verify webhook payload based on alert type
            verifyPayload(alertType, 1, "Trace Feedback Score",
                    List.of("*Traces Feedback Scores:*\n",
                            "Trace Score",
                            feedbackScore.name(),
                            String.format("%.2f", feedbackScore.value()),
                            expectedUrl));
        }

        @ParameterizedTest
        @MethodSource("alertTypeProvider")
        @DisplayName("Success: should send webhook formatted thread feedback score event")
        void testThreadFeedbackScoreEvent(AlertType alertType) {
            // Given
            var mock = prepareMockWorkspace();

            // Create a project
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, mock.getLeft(), mock.getRight());

            // Create alert with webhook
            var alert = createAlertForEvent(AlertTrigger.builder()
                    .eventType(AlertEventType.TRACE_THREAD_FEEDBACK_SCORE)
                    .build(), alertType);

            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            // Create a thread with multiple traces
            var threadId = UUID.randomUUID().toString();
            var traces = IntStream.range(0, 3)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .threadId(threadId)
                            .projectName(project.name())
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(traces, mock.getLeft(), mock.getRight());

            // Wait for the thread to be created
            Awaitility.await().untilAsserted(() -> {
                var thread = traceResourceClient.getTraceThread(threadId, projectId, mock.getLeft(), mock.getRight());
                assertThat(thread.threadModelId()).isNotNull();
            });

            // Get the thread
            var thread = traceResourceClient.getTraceThread(threadId, projectId, mock.getLeft(), mock.getRight());

            // Close thread
            traceResourceClient.closeTraceThread(thread.id(), projectId, project.name(), mock.getLeft(),
                    mock.getRight());

            // Create a feedback score for the thread
            FeedbackScore feedbackScore = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                    .source(ScoreSource.SDK)
                    .build();

            var feedbackScoreBatchItem = FeedbackScoreBatchItemThread.builder()
                    .threadId(thread.id())
                    .projectName(project.name())
                    .name(feedbackScore.name())
                    .value(feedbackScore.value())
                    .reason(feedbackScore.reason())
                    .source(feedbackScore.source())
                    .build();

            traceResourceClient.threadFeedbackScores(List.of(feedbackScoreBatchItem), mock.getLeft(), mock.getRight());

            // Construct expected URL
            String expectedUrl = String.format(
                    BASE_URL + "/%s/projects/%s/traces?type=threads&thread=%s&threadTab=feedback_scores",
                    mock.getRight(), projectId, thread.id());

            // Verify webhook payload based on alert type
            verifyPayload(alertType, 1, "Thread Feedback Score",
                    List.of("*Threads Feedback Scores:*\n",
                            "Thread Score",
                            feedbackScore.name(),
                            String.format("%.2f", feedbackScore.value()),
                            expectedUrl));
        }

        @ParameterizedTest
        @MethodSource("alertTypeProvider")
        @DisplayName("Success: should send webhook formatted guardrails triggered event")
        void testGuardrailsTriggeredEvent(AlertType alertType) {
            // Given
            var mock = prepareMockWorkspace();

            // Create a project
            String projectName = RandomStringUtils.randomAlphabetic(10);
            UUID projectId = projectResourceClient.createProject(projectName, mock.getLeft(), mock.getRight());

            // Create alert with webhook
            var alert = createAlertForEvent(AlertTrigger.builder()
                    .eventType(AlertEventType.TRACE_GUARDRAILS_TRIGGERED)
                    .build(), alertType);

            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            // Create a trace
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .usage(null)
                    .visibilityMode(null)
                    .build();
            traceResourceClient.createTrace(trace, mock.getLeft(), mock.getRight());

            // Create guardrails for the trace
            List<Guardrail> guardrails = IntStream.range(0, 2)
                    .mapToObj(i -> factory.manufacturePojo(Guardrail.class).toBuilder()
                            .entityId(trace.id())
                            .secondaryId(UUID.randomUUID())
                            .projectName(projectName)
                            .projectId(projectId)
                            .result(GuardrailResult.FAILED)
                            .build())
                    .toList();
            guardrailsResourceClient.addBatch(guardrails, mock.getLeft(), mock.getRight());

            // Construct expected URL
            String expectedUrl = String.format(BASE_URL + "/%s/projects/%s/traces?trace=%s",
                    mock.getRight(), projectId, trace.id());

            // Verify webhook payload based on alert type
            verifyPayload(alertType, 1, "Guardrail Triggered",
                    List.of("*Traces with Guardrails Triggered:*\n", expectedUrl));
        }

        @Test
        @DisplayName("Success: should send webhook with fallback block when trace errors exceed Slack text limit")
        void testTraceErrorsEventWithFallback() {
            // Given
            var mock = prepareMockWorkspace();

            // Create a project
            String projectName = RandomStringUtils.randomAlphabetic(10);
            projectResourceClient.createProject(projectName, mock.getLeft(), mock.getRight());

            // Create alert with webhook for Slack only
            var alert = createAlertForEvent(AlertTrigger.builder()
                    .eventType(AlertEventType.TRACE_ERRORS)
                    .build(), AlertType.SLACK);

            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            // Create many traces with errors to exceed Slack's 3000 character limit
            // Each trace URL is ~150 characters, so we need ~20 traces to exceed the limit
            List<Trace> tracesWithErrors = IntStream.range(0, 25)
                    .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .visibilityMode(null)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(tracesWithErrors, mock.getLeft(), mock.getRight());

            // Verify webhook was called
            var slackPayload = verifyWebhookCalledAndGetPayload(SlackWebhookPayload.class);

            String url = BASE_URL + "/" + mock.getRight() + "/projects";
            String fallbackText = String.format("Overall %d Traces with errors created, you could check them here: <%s|View All>",
                    tracesWithErrors.size(), url);

            // Verify Slack payload has fallback block due to text truncation
            verifySlackBlockStructureWithFallback(slackPayload, fallbackText);
        }

        @Test
        @DisplayName("Success: should send webhook with fallback block when guardrails exceed Slack text limit")
        void testGuardrailsTriggeredEventWithFallback() {
            // Given
            var mock = prepareMockWorkspace();

            // Create a project
            String projectName = RandomStringUtils.randomAlphabetic(10);
            UUID projectId = projectResourceClient.createProject(projectName, mock.getLeft(), mock.getRight());

            // Create alert with webhook for Slack only
            var alert = createAlertForEvent(AlertTrigger.builder()
                    .eventType(AlertEventType.TRACE_GUARDRAILS_TRIGGERED)
                    .build(), AlertType.SLACK);

            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            // Create guardrails for each trace
            List<Guardrail> guardrails = IntStream.range(0, 25)
                    .mapToObj(i -> {
                        Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                                .projectName(projectName)
                                .usage(null)
                                .visibilityMode(null)
                                .build();
                        traceResourceClient.createTrace(trace, mock.getLeft(), mock.getRight());

                        return factory.manufacturePojo(Guardrail.class).toBuilder()
                                .entityId(trace.id())
                                .secondaryId(UUID.randomUUID())
                                .projectName(projectName)
                                .projectId(projectId)
                                .result(GuardrailResult.FAILED)
                                .build();
                    }).toList();

            guardrailsResourceClient.addBatch(guardrails, mock.getLeft(), mock.getRight());

            // Verify webhook was called
            var slackPayload = verifyWebhookCalledAndGetPayload(SlackWebhookPayload.class);

            String url = BASE_URL + "/" + mock.getRight() + "/projects";
            String fallbackText = String.format(
                    "Overall %d Traces with Guardrails Triggered created, you could check them here: <%s|View All>",
                    guardrails.size(), url);

            // Verify Slack payload has fallback block due to text truncation
            verifySlackBlockStructureWithFallback(slackPayload, fallbackText);
        }

        @Test
        @DisplayName("Success: should send webhook with fallback block when prompts created exceed Slack text limit")
        void testPromptCreatedEventWithFallback() {
            // Given
            var mock = prepareMockWorkspace();

            // Create alert with webhook for Slack only
            var alert = createAlertForEvent(AlertTrigger.builder()
                    .eventType(PROMPT_CREATED)
                    .build(), AlertType.SLACK);

            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            // Create many prompts to exceed Slack's 3000 character limit
            // Each prompt URL is ~120 characters, so we need ~25 prompts to exceed the limit
            int promptsCnt = 30;
            for (int i = 0; i < promptsCnt; i++) {
                var prompt = factory.manufacturePojo(Prompt.class)
                        .toBuilder()
                        .versionCount(0L)
                        .createdBy(USER)
                        .lastUpdatedBy(USER)
                        .build();
                promptResourceClient.createPrompt(prompt, mock.getLeft(), mock.getRight());
            }

            // Verify webhook was called
            var slackPayload = verifyWebhookCalledAndGetPayload(SlackWebhookPayload.class);

            // Verify Slack payload has fallback block due to text truncation
            String url = BASE_URL + "/" + mock.getRight() + "/prompts";
            String fallbackText = String.format("Overall %d Prompts created, you could check them here: <%s|View All>",
                    promptsCnt, url);
            verifySlackBlockStructureWithFallback(slackPayload, fallbackText);
        }

        @Test
        @DisplayName("Success: should send webhook with fallback block when prompt commits exceed Slack text limit")
        void testPromptCommittedEventWithFallback() {
            // Given
            var mock = prepareMockWorkspace();

            // Create alert with webhook for Slack only
            var alert = createAlertForEvent(AlertTrigger.builder()
                    .eventType(PROMPT_COMMITTED)
                    .build(), AlertType.SLACK);

            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            // Create many prompt commits to exceed Slack's 3000 character limit
            // Each commit URL is ~140 characters, so we need ~25 commits to exceed the limit
            int commitsCnt = 30;
            for (int i = 0; i < commitsCnt; i++) {
                var prompt = factory.manufacturePojo(Prompt.class);
                promptResourceClient.createPromptVersion(prompt, mock.getLeft(), mock.getRight());
            }

            // Verify webhook was called
            var slackPayload = verifyWebhookCalledAndGetPayload(SlackWebhookPayload.class);

            // Verify Slack payload has fallback block due to text truncation
            String url = BASE_URL + "/" + mock.getRight() + "/prompts";
            String fallbackText = String.format("Overall %d Prompts commits created, you could check them here: <%s|View All>",
                    commitsCnt, url);
            verifySlackBlockStructureWithFallback(slackPayload, fallbackText);
        }

        @Test
        @DisplayName("Success: should send webhook with fallback block when trace feedback scores exceed Slack text limit")
        void testTraceFeedbackScoreEventWithFallback() {
            // Given
            var mock = prepareMockWorkspace();

            // Create a project
            String projectName = RandomStringUtils.randomAlphabetic(10);
            projectResourceClient.createProject(projectName, mock.getLeft(), mock.getRight());

            // Create alert with webhook for Slack only
            var alert = createAlertForEvent(AlertTrigger.builder()
                    .eventType(AlertEventType.TRACE_FEEDBACK_SCORE)
                    .build(), AlertType.SLACK);

            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            // Create many traces with feedback scores to exceed Slack's 3000 character limit
            // Each feedback score line is ~180 characters, so we need ~17 scores to exceed the limit
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .build();
            traceResourceClient.createTrace(trace, mock.getLeft(), mock.getRight());

            int scoresCnt = 25;
            IntStream.range(0, scoresCnt).forEach(i -> {
                FeedbackScore feedbackScore = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                        .source(ScoreSource.SDK)
                        .build();
                traceResourceClient.feedbackScore(trace.id(), feedbackScore, mock.getRight(), mock.getLeft());
            });

            // Verify webhook was called
            var slackPayload = verifyWebhookCalledAndGetPayload(SlackWebhookPayload.class);

            // Verify Slack payload has fallback block due to text truncation
            String url = BASE_URL + "/" + mock.getRight() + "/projects";
            String fallbackText = String.format(
                    "Overall %d Traces Feedback Scores created, you could check them here: <%s|View All>",
                    scoresCnt, url);
            verifySlackBlockStructureWithFallback(slackPayload, fallbackText);
        }

        @Test
        @DisplayName("Success: should send webhook with fallback block when thread feedback scores exceed Slack text limit")
        void testThreadFeedbackScoreEventWithFallback() {
            // Given
            var mock = prepareMockWorkspace();

            // Create a project
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, mock.getLeft(), mock.getRight());

            // Create alert with webhook for Slack only
            var alert = createAlertForEvent(AlertTrigger.builder()
                    .eventType(AlertEventType.TRACE_THREAD_FEEDBACK_SCORE)
                    .build(), AlertType.SLACK);

            alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            // Create many threads with feedback scores to exceed Slack's 3000 character limit
            // Each feedback score line is ~190 characters, so we need ~16 scores to exceed the limit
            int scoresCnt = 25;

            var threadId = UUID.randomUUID().toString();
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .threadId(threadId)
                    .projectName(project.name())
                    .build();
            traceResourceClient.createTrace(trace, mock.getLeft(), mock.getRight());

            // Wait for the thread to be created and close it
            Awaitility.await().untilAsserted(() -> {
                var thread = traceResourceClient.getTraceThread(threadId, projectId, mock.getLeft(),
                        mock.getRight());
                assertThat(thread.threadModelId()).isNotNull();

                traceResourceClient.closeTraceThread(thread.id(), projectId, project.name(), mock.getLeft(),
                        mock.getRight());

                IntStream.range(0, scoresCnt).forEach(i -> {
                    // Create feedback score for the thread
                    FeedbackScore feedbackScore = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                            .source(ScoreSource.SDK)
                            .build();

                    var feedbackScoreBatchItem = FeedbackScoreBatchItemThread.builder()
                            .threadId(thread.id())
                            .projectName(project.name())
                            .name(feedbackScore.name())
                            .value(feedbackScore.value())
                            .reason(feedbackScore.reason())
                            .source(feedbackScore.source())
                            .build();

                    traceResourceClient.threadFeedbackScores(List.of(feedbackScoreBatchItem), mock.getLeft(),
                            mock.getRight());
                });
            });

            // Verify webhook was called
            var slackPayload = verifyWebhookCalledAndGetPayload(SlackWebhookPayload.class);

            // Verify Slack payload has fallback block due to text truncation
            String url = BASE_URL + "/" + mock.getRight() + "/projects";
            String fallbackText = String.format(
                    "Overall %d Threads Feedback Scores created, you could check them here: <%s|View All>",
                    scoresCnt, url);
            verifySlackBlockStructureWithFallback(slackPayload, fallbackText);
        }
    }

    @Nested
    @DisplayName("Get Webhook Examples:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetWebhookExamples {

        @ParameterizedTest
        @MethodSource("alertTypeProvider")
        @DisplayName("Success: should return webhook examples for all event types with different alert types")
        void getWebhookExamples__whenCalled__thenReturnExamplesForAllEventTypes(String testName, AlertType alertType) {
            // Given
            var mock = prepareMockWorkspace();

            // When
            var webhookExamples = alertResourceClient.getWebhookExamples(mock.getLeft(), mock.getRight(),
                    alertType, HttpStatus.SC_OK);

            // Then
            assertThat(webhookExamples).isNotNull();
            assertThat(webhookExamples.responseExamples()).isNotNull();
            assertThat(webhookExamples.responseExamples()).isNotEmpty();

            // Verify that all alert event types have examples
            assertThat(webhookExamples.responseExamples().keySet()).containsExactlyInAnyOrder(
                    AlertEventType.TRACE_ERRORS,
                    AlertEventType.TRACE_FEEDBACK_SCORE,
                    AlertEventType.TRACE_THREAD_FEEDBACK_SCORE,
                    AlertEventType.PROMPT_CREATED,
                    AlertEventType.PROMPT_COMMITTED,
                    AlertEventType.TRACE_GUARDRAILS_TRIGGERED,
                    AlertEventType.PROMPT_DELETED);

            // Verify that each example is a non-empty string
            webhookExamples.responseExamples().values().forEach(example -> {
                assertThat(example).isNotNull();
            });
        }

        private Stream<Arguments> alertTypeProvider() {
            return Stream.of(
                    Arguments.of("alertType is null (defaults to GENERAL)", null),
                    Arguments.of("alertType is GENERAL", AlertType.GENERAL),
                    Arguments.of("alertType is SLACK", AlertType.SLACK),
                    Arguments.of("alertType is PAGERDUTY", AlertType.PAGERDUTY));
        }
    }

    private Pair<String, String> prepareMockWorkspace() {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        return Pair.of(apiKey, workspaceName);
    }

    private void compareAlerts(Alert expected, Alert actual, boolean decryptSecretToken) {
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

        if (decryptSecretToken) {
            // We should decrypt secretToken in order to compare, since it encrypts on deserialization
            assertThat(decrypt(actual.webhook().secretToken())).isEqualTo(maskApiKey(expected.webhook().secretToken()));
        } else {
            assertThat(actual.webhook().secretToken()).isEqualTo(expected.webhook().secretToken());
        }

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
                .secretToken(UUID.randomUUID().toString())
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
            int expectedTotal, int page, List<SortingField> sortingFields, List<AlertFilter> filters,
            boolean decryptSecretToken) {

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
            compareAlerts(expectedAlerts.get(i), alertPage.content().get(i), decryptSecretToken);
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
                SortableFields.WEBHOOK_URL);
    }
}
