package com.comet.opik.api.resources.v1.internal;

import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceCountResponse;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

@DisplayName("Usage Resource Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
@ExtendWith(DropwizardAppExtensionProvider.class)
class UsageResourceTest {

    public static final String USAGE_RESOURCE_URL_TEMPLATE = "%s/v1/internal/usage";
    public static final String TRACE_RESOURCE_URL_TEMPLATE = "%s/v1/private/traces";
    private static final String EXPERIMENT_RESOURCE_URL_TEMPLATE = "%s/v1/private/experiments";
    private static final String DATASET_RESOURCE_URL_TEMPLATE = "%s/v1/private/datasets";

    private final String USER = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private TransactionTemplateAsync clickHouseTemplate;
    private TransactionTemplate mySqlTemplate;
    private ExperimentResourceClient experimentResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi, TransactionTemplateAsync clickHouseTemplate,
            TransactionTemplate mySqlTemplate) throws SQLException {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;
        this.clickHouseTemplate = clickHouseTemplate;
        this.mySqlTemplate = mySqlTemplate;

        ClientSupportUtils.config(client);

        this.experimentResourceClient = new ExperimentResourceClient(client, baseURI, factory);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @Nested
    @DisplayName("Opik usage:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Usage {

        @Test
        @DisplayName("Get traces count on previous day for all workspaces, no Auth")
        void tracesCountForWorkspace() {
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .build())
                    .toList();

            // Setup mock workspace with traces
            var workspaceId = UUID.randomUUID().toString();
            var apikey = UUID.randomUUID().toString();
            int tracesCount = setupEntitiesForWorkspace(workspaceId, apikey, traces,
                    TRACE_RESOURCE_URL_TEMPLATE);

            // Change created_at to the previous day in order to capture those traces in count query, since for Stripe we need to count it daily for yesterday
            subtractClickHouseTableRecordsCreatedAtOneDay("traces").accept(workspaceId);

            // Setup second workspace with traces, but leave created_at date set to today, so traces do not end up in the pool
            var workspaceIdForToday = UUID.randomUUID().toString();
            var apikey2 = UUID.randomUUID().toString();

            setupEntitiesForWorkspace(workspaceIdForToday, apikey2, traces, TRACE_RESOURCE_URL_TEMPLATE);

            try (var actualResponse = client.target(USAGE_RESOURCE_URL_TEMPLATE.formatted(baseURI))
                    .path("/workspace-trace-counts")
                    .request()
                    .get()) {

                var response = validateResponse(actualResponse, TraceCountResponse.class);

                var workspaceTraceCount = getMatch(response.workspacesTracesCount(),
                        wtc -> wtc.workspace().equals(workspaceId));

                assertThat(workspaceTraceCount).isPresent();
                assertThat(workspaceTraceCount.get())
                        .isEqualTo(new TraceCountResponse.WorkspaceTraceCount(workspaceId, tracesCount));

                // Check that today's workspace is not returned
                var workspaceTraceCountToday = getMatch(response.workspacesTracesCount(),
                        wtc -> wtc.workspace().equals(workspaceIdForToday));
                assertThat(workspaceTraceCountToday).isEmpty();
            }
        }

        @Test
        @DisplayName("Get traces daily info for BI events, no Auth")
        void traceBiInfoTest() {
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .build())
                    .toList();
            biInfoTest(traces, TRACE_RESOURCE_URL_TEMPLATE, "traces",
                    subtractClickHouseTableRecordsCreatedAtOneDay("traces"));
        }

        @Test
        @DisplayName("Get experiments daily info for BI events, no Auth")
        void experimentBiInfoTest() {
            var experiments = experimentResourceClient.generateExperimentList();
            biInfoTest(experiments, EXPERIMENT_RESOURCE_URL_TEMPLATE, "experiments",
                    subtractClickHouseTableRecordsCreatedAtOneDay("experiments"));
        }

        @Test
        @DisplayName("Get datasets daily info for BI events, no Auth")
        void datasetBiInfoTest() {
            var datasets = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class)
                    .stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .build())
                    .toList();
            biInfoTest(datasets, DATASET_RESOURCE_URL_TEMPLATE, "datasets",
                    subtractDatasetRecordsCreatedAtOneDay());
        }

        private <T> void biInfoTest(List<T> entities, String resourseUri, String biType,
                Consumer<String> decreaseTableRecordsCreatedAt) {
            // Setup mock workspace with corresponding entities
            var workspaceId = UUID.randomUUID().toString();
            var apikey = UUID.randomUUID().toString();
            int entitiesCount = setupEntitiesForWorkspace(workspaceId, apikey, entities,
                    resourseUri);

            // Change created_at to the previous day in order to capture those entities in count query, since for BI events we need to count it daily for yesterday
            decreaseTableRecordsCreatedAt.accept(workspaceId);

            await().atMost(10, SECONDS).until(() -> {
                try (var actualResponse = client.target(USAGE_RESOURCE_URL_TEMPLATE.formatted(baseURI))
                        .path("bi-%s".formatted(biType))
                        .request()
                        .get()) {

                    var response = validateResponse(actualResponse, BiInformationResponse.class);
                    var biInformation = getMatch(response.biInformation(),
                            biInfo -> biInfo.workspaceId().equals(workspaceId));

                    return biInformation
                            .map(biInfo -> biInfo
                                    .equals(BiInformationResponse.BiInformation.builder()
                                            .workspaceId(workspaceId)
                                            .user(USER)
                                            .count(entitiesCount)
                                            .build()))
                            .orElse(false);
                }
            });
        }
    }

    private <T> int setupEntitiesForWorkspace(String workspaceId, String okApikey, List<T> entities,
            String resourseUri) {
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(okApikey, workspaceName, workspaceId);

        entities.forEach(entity -> createEntity(entity, okApikey, workspaceName, resourseUri));

        return entities.size();
    }

    private <T> void createEntity(T entity, String apiKey, String workspaceName, String resourseUri) {
        try (var actualResponse = client.target(resourseUri.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(entity))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    private <T> Optional<T> getMatch(List<T> list, Predicate<T> predicate) {
        return list.stream()
                .filter(predicate)
                .findFirst();
    }

    private <T> T validateResponse(Response response, Class<T> entityType) {
        assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(200);
        assertThat(response.hasEntity()).isTrue();

        return response.readEntity(entityType);
    }

    private Consumer<String> subtractClickHouseTableRecordsCreatedAtOneDay(String table) {
        // Change created_at to the previous day in order to capture this data in query
        return workspaceId -> {
            String updateCreatedAt = "ALTER TABLE %s UPDATE created_at = subtractDays(created_at, 1) WHERE workspace_id=:workspace_id;"
                    .formatted(table);
            clickHouseTemplate.nonTransaction(connection -> {
                var statement = connection.createStatement(updateCreatedAt)
                        .bind("workspace_id", workspaceId);
                return Mono.from(statement.execute());
            }).block();
        };
    }

    private Consumer<String> subtractDatasetRecordsCreatedAtOneDay() {
        // Change created_at to the previous day in order to capture this data in query
        return workspaceId -> {
            mySqlTemplate.inTransaction(WRITE, handle -> {
                handle.createUpdate(
                        "UPDATE datasets SET created_at = TIMESTAMPADD(DAY, -1, created_at) WHERE workspace_id=:workspace_id")
                        .bind("workspace_id", workspaceId)
                        .execute();

                return null;
            });
        };
    }
}
