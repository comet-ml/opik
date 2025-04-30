package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DeleteIdsHolder;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.OptimizationResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
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
import org.testcontainers.shaded.org.awaitility.Awaitility;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Dataset Event Listener")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetEventListenerTest {

    private static final String BASE_RESOURCE_URI = "%s/v1/private/datasets";
    private static final String EXPERIMENT_RESOURCE_URI = "%s/v1/private/experiments";

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private ExperimentResourceClient experimentResourceClient;
    private OptimizationResourceClient optimizationResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws Exception {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        experimentResourceClient = new ExperimentResourceClient(client, baseURI, factory);
        optimizationResourceClient = new OptimizationResourceClient(client, baseURI, factory);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    private UUID createAndAssert(Dataset dataset, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(dataset))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
            assertThat(actualResponse.hasEntity()).isFalse();

            return TestUtils.getIdFromLocation(actualResponse.getLocation());
        }
    }

    private void createAndAssert(Experiment expectedExperiment, String apiKey, String workspaceName) {

        try (var actualResponse = client.target(EXPERIMENT_RESOURCE_URI.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(expectedExperiment))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);

            var actualId = TestUtils.getIdFromLocation(actualResponse.getLocation());

            assertThat(actualResponse.hasEntity()).isFalse();

            assertThat(actualId).isEqualTo(expectedExperiment.id());
        }
    }

    private Experiment getExperiment(UUID id, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(EXPERIMENT_RESOURCE_URI.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            if (actualResponse.getStatusInfo().getStatusCode() == 404) {
                return null;
            }

            return actualResponse.readEntity(Experiment.class);
        }
    }

    private Dataset getDataset(UUID id, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            if (actualResponse.getStatusInfo().getStatusCode() == 404) {
                return null;
            }

            return actualResponse.readEntity(Dataset.class);
        }
    }

    private void deleteAndAssert(Set<UUID> ids, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(EXPERIMENT_RESOURCE_URI.formatted(baseURI))
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new DeleteIdsHolder(ids)))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateExperimentEvent {

        @Test
        @DisplayName("when a new experiment is created, it should be saved in the database")
        void when__newExperimentIsCreated__shouldBeSavedInTheDatabase() {
            var dataset = factory.manufacturePojo(Dataset.class);
            var datasetId = createAndAssert(dataset, API_KEY, TEST_WORKSPACE);

            var expectedExperiment = generateExperiment(dataset);

            createAndAssert(expectedExperiment, API_KEY, TEST_WORKSPACE);

            var actualExperiment = getExperiment(expectedExperiment.id(), TEST_WORKSPACE, API_KEY);

            var actualDataset = getDataset(datasetId, TEST_WORKSPACE, API_KEY);

            Awaitility.await().untilAsserted(() -> {
                assertThat(actualDataset.lastCreatedExperimentAt())
                        .isCloseTo(actualExperiment.createdAt(), within(2, ChronoUnit.SECONDS));
            });
        }
    }

    private Experiment generateExperiment(Dataset dataset) {
        return experimentResourceClient.createPartialExperiment()
                .datasetName(dataset.name())
                .build();
    }

    private Optimization generateOptimization(Dataset dataset) {
        return optimizationResourceClient.createPartialOptimization()
                .datasetName(dataset.name())
                .build();
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteExperimentEvent {

        @Test
        @DisplayName("when an experiment is deleted, the last created experiment date should be updated")
        void when__experimentIsDeleted__lastCreatedExperimentDateShouldBeUpdated() {
            var dataset = factory.manufacturePojo(Dataset.class);
            var datasetId = createAndAssert(dataset, API_KEY, TEST_WORKSPACE);

            var dataset2 = factory.manufacturePojo(Dataset.class);
            var datasetId2 = createAndAssert(dataset2, API_KEY, TEST_WORKSPACE);

            var expectedExperiment = generateExperiment(dataset);

            var expectedExperiment2 = generateExperiment(dataset2);

            var expectedExperiment3 = generateExperiment(dataset);

            var expectedExperiment4 = generateExperiment(dataset2);

            createAndAssert(expectedExperiment, API_KEY, TEST_WORKSPACE);
            createAndAssert(expectedExperiment2, API_KEY, TEST_WORKSPACE);
            createAndAssert(expectedExperiment3, API_KEY, TEST_WORKSPACE);
            createAndAssert(expectedExperiment4, API_KEY, TEST_WORKSPACE);

            var actualExperiment = getExperiment(expectedExperiment.id(), TEST_WORKSPACE, API_KEY);
            var actualExperiment2 = getExperiment(expectedExperiment2.id(), TEST_WORKSPACE, API_KEY);
            var actualExperiment3 = getExperiment(expectedExperiment3.id(), TEST_WORKSPACE, API_KEY);
            var actualExperiment4 = getExperiment(expectedExperiment4.id(), TEST_WORKSPACE, API_KEY);

            Awaitility.await().untilAsserted(() -> {
                var actualDataset = getDataset(datasetId, TEST_WORKSPACE, API_KEY);

                assertThat(actualDataset.lastCreatedExperimentAt())
                        .isCloseTo(actualExperiment3.createdAt(), within(2, ChronoUnit.SECONDS));

                var actualDataset2 = getDataset(datasetId2, TEST_WORKSPACE, API_KEY);

                assertThat(actualDataset2.lastCreatedExperimentAt())
                        .isCloseTo(actualExperiment4.createdAt(), within(2, ChronoUnit.SECONDS));
            });

            deleteAndAssert(
                    Set.of(
                            expectedExperiment4.id(),
                            expectedExperiment3.id()),
                    TEST_WORKSPACE, API_KEY);

            Awaitility.await().untilAsserted(() -> {
                var actualDataset = getDataset(datasetId, TEST_WORKSPACE, API_KEY);

                assertThat(actualDataset.lastCreatedExperimentAt())
                        .isCloseTo(actualExperiment.createdAt(), within(2, ChronoUnit.SECONDS));

                var actualDataset2 = getDataset(datasetId2, TEST_WORKSPACE, API_KEY);

                assertThat(actualDataset2.lastCreatedExperimentAt())
                        .isCloseTo(actualExperiment2.createdAt(), within(2, ChronoUnit.SECONDS));
            });
        }

        @Test
        @DisplayName("when an experiment is deleted, the last created experiment date should be updated")
        void when__experimentIsDeleted__lastCreatedExperimentDateShouldBeUpdated_() {
            var dataset = factory.manufacturePojo(Dataset.class);
            var datasetId = createAndAssert(dataset, API_KEY, TEST_WORKSPACE);

            var expectedExperiment = generateExperiment(dataset);

            createAndAssert(expectedExperiment, API_KEY, TEST_WORKSPACE);

            var actualExperiment = getExperiment(expectedExperiment.id(), TEST_WORKSPACE, API_KEY);

            Awaitility.await().untilAsserted(() -> {
                var actualDataset = getDataset(datasetId, TEST_WORKSPACE, API_KEY);

                assertThat(actualDataset.lastCreatedExperimentAt())
                        .isCloseTo(actualExperiment.createdAt(), within(2, ChronoUnit.SECONDS));
            });

            deleteAndAssert(Set.of(actualExperiment.id()), TEST_WORKSPACE, API_KEY);

            Awaitility.await().untilAsserted(() -> {
                var actualDataset = getDataset(datasetId, TEST_WORKSPACE, API_KEY);

                assertThat(actualDataset.lastCreatedExperimentAt()).isNull();
            });
        }
    }

    @Test
    @DisplayName("when a new optimization is created, it should be saved in the database")
    void when__newOptimizationIsCreated__shouldBeSavedInTheDatabase() {
        var dataset = factory.manufacturePojo(Dataset.class);
        var datasetId = createAndAssert(dataset, API_KEY, TEST_WORKSPACE);

        var expectedOptimization = generateOptimization(dataset);
        var optimizationId = optimizationResourceClient.create(expectedOptimization, API_KEY, TEST_WORKSPACE);

        // get optimization by id and compare
        var actualOptimization = optimizationResourceClient.get(optimizationId, API_KEY, TEST_WORKSPACE, 200);

        Awaitility.await().untilAsserted(() -> {
            var actualDataset = getDataset(datasetId, TEST_WORKSPACE, API_KEY);
            assertThat(actualDataset.lastCreatedOptimizationAt())
                    .isCloseTo(actualOptimization.createdAt(), within(2, ChronoUnit.SECONDS));
        });
    }

    @Test
    @DisplayName("when an optimization is deleted, the last created optimization date should be updated")
    void when__optimizationIsDeleted__lastCreatedOptimizationDateShouldBeUpdated_() {
        var dataset = factory.manufacturePojo(Dataset.class);
        var datasetId = createAndAssert(dataset, API_KEY, TEST_WORKSPACE);

        var expectedOptimization = generateOptimization(dataset);
        var optimizationId = optimizationResourceClient.create(expectedOptimization, API_KEY, TEST_WORKSPACE);

        // get optimization by id and compare
        var actualOptimization = optimizationResourceClient.get(optimizationId, API_KEY, TEST_WORKSPACE, 200);

        Awaitility.await().untilAsserted(() -> {
            var actualDataset = getDataset(datasetId, TEST_WORKSPACE, API_KEY);
            assertThat(actualDataset.lastCreatedOptimizationAt())
                    .isCloseTo(actualOptimization.createdAt(), within(2, ChronoUnit.SECONDS));
        });

        optimizationResourceClient.delete(Set.of(optimizationId), API_KEY, TEST_WORKSPACE);

        Awaitility.await().untilAsserted(() -> {
            var actualDataset = getDataset(datasetId, TEST_WORKSPACE, API_KEY);
            assertThat(actualDataset.lastCreatedOptimizationAt()).isNull();
        });
    }
}