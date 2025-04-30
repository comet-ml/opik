package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.Dataset.DatasetPage;
import com.comet.opik.api.DeleteIdsHolder;
import com.comet.opik.api.Experiment;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Dataset Experiments E2E Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetExperimentE2ETest {

    private static final String BASE_RESOURCE_URI = "%s/v1/private/datasets";
    private static final String EXPERIMENT_RESOURCE_URI = "%s/v1/private/experiments";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

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

        this.experimentResourceClient = new ExperimentResourceClient(client, baseURI, factory);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId,
                UUID.randomUUID().toString());
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

    private DatasetPage getDatasets(String workspaceName, String apiKey) {
        try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .queryParam("with_experiments_only", true)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            return actualResponse.readEntity(DatasetPage.class);
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

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FilterDatasetsByExperimentWith {

        @Test
        @DisplayName("when filtering by datasets with experiments, then should return the dataset with experiments")
        void when__filteringByDatasetsWithExperiments__thenShouldReturnTheDatasetWithExperiments() {

            String apiKey = UUID.randomUUID().toString();
            String testWorkspace = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, testWorkspace, workspaceId);

            var dataset = factory.manufacturePojo(Dataset.class);
            var datasetId = createAndAssert(dataset, apiKey, testWorkspace);

            var dataset2 = factory.manufacturePojo(Dataset.class);
            createAndAssert(dataset2, apiKey, testWorkspace);

            var dataset3 = factory.manufacturePojo(Dataset.class);
            var datasetId3 = createAndAssert(dataset3, apiKey, testWorkspace);

            var expectedExperiment = generateExperiment(dataset);

            var expectedExperiment3 = generateExperiment(dataset3);

            createAndAssert(expectedExperiment, apiKey, testWorkspace);
            createAndAssert(expectedExperiment3, apiKey, testWorkspace);

            Awaitility.await().untilAsserted(() -> {
                DatasetPage datasets = getDatasets(testWorkspace, apiKey);

                assertPage(datasets, List.of(datasetId3, datasetId));
            });
        }

        @Test
        @DisplayName("when filtering by datasets with experiments after an experiment is deleted, then should return the dataset with experiments")
        void when__filteringByDatasetsWithExperimentsAfterAnExperimentIsDeleted__thenShouldReturnTheDatasetWithExperiments() {

            String apiKey = UUID.randomUUID().toString();
            String testWorkspace = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, testWorkspace, workspaceId);

            var dataset = factory.manufacturePojo(Dataset.class);
            var datasetId = createAndAssert(dataset, apiKey, testWorkspace);

            var dataset2 = factory.manufacturePojo(Dataset.class);
            createAndAssert(dataset2, apiKey, testWorkspace);

            var dataset3 = factory.manufacturePojo(Dataset.class);
            var datasetId3 = createAndAssert(dataset3, apiKey, testWorkspace);

            var expectedExperiment = generateExperiment(dataset);

            var expectedExperiment2 = generateExperiment(dataset2);

            var expectedExperiment3 = generateExperiment(dataset3);

            createAndAssert(expectedExperiment, apiKey, testWorkspace);
            createAndAssert(expectedExperiment2, apiKey, testWorkspace);
            createAndAssert(expectedExperiment3, apiKey, testWorkspace);

            deleteAndAssert(Set.of(expectedExperiment2.id()), testWorkspace, apiKey);

            Awaitility.await().untilAsserted(() -> {
                DatasetPage datasets = getDatasets(testWorkspace, apiKey);

                assertPage(datasets, List.of(datasetId3, datasetId));
            });
        }

        @Test
        @DisplayName("when filtering by datasets with experiments after deleting experiments but dataset has more, then should return the dataset with experiments")
        void when__filteringByDatasetsWithExperimentsAfterDeletingExperimentsButDatasetHasMore__thenShouldReturnTheDatasetWithExperiments() {

            String apiKey = UUID.randomUUID().toString();
            String testWorkspace = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, testWorkspace, workspaceId);

            var dataset = factory.manufacturePojo(Dataset.class);
            var datasetId = createAndAssert(dataset, apiKey, testWorkspace);

            var dataset2 = factory.manufacturePojo(Dataset.class);
            var datasetId2 = createAndAssert(dataset2, apiKey, testWorkspace);

            var dataset3 = factory.manufacturePojo(Dataset.class);
            var datasetId3 = createAndAssert(dataset3, apiKey, testWorkspace);

            var experiment = generateExperiment(dataset);

            var experiment2 = generateExperiment(dataset2);

            var experiment3 = generateExperiment(dataset3);

            var experiment4 = generateExperiment(dataset2);

            createAndAssert(experiment, apiKey, testWorkspace);
            createAndAssert(experiment2, apiKey, testWorkspace);
            createAndAssert(experiment3, apiKey, testWorkspace);
            createAndAssert(experiment4, apiKey, testWorkspace);

            Awaitility.await().untilAsserted(() -> {
                DatasetPage datasets = getDatasets(testWorkspace, apiKey);

                assertPage(datasets, List.of(datasetId3, datasetId2, datasetId));
            });

            deleteAndAssert(Set.of(experiment4.id()), testWorkspace, apiKey);

            var expectedExperiment = getExperiment(experiment2.id(), testWorkspace, apiKey);

            Awaitility.await().untilAsserted(() -> {
                var actualDataset = getDataset(datasetId2, testWorkspace, apiKey);

                assertThat(actualDataset.lastCreatedExperimentAt())
                        .isCloseTo(expectedExperiment.createdAt(), within(1, ChronoUnit.MICROS));
            });

            DatasetPage datasets = getDatasets(testWorkspace, apiKey);

            assertPage(datasets, List.of(datasetId3, datasetId2, datasetId));
        }
    }

    private Experiment generateExperiment(Dataset dataset) {
        return experimentResourceClient.createPartialExperiment()
                .datasetName(dataset.name())
                .build();
    }

    private static void assertPage(DatasetPage actualDataset, List<UUID> expectedIdOrder) {
        assertThat(actualDataset.total()).isEqualTo(expectedIdOrder.size());
        assertThat(actualDataset.page()).isEqualTo(1);
        assertThat(actualDataset.size()).isEqualTo(expectedIdOrder.size());

        assertThat(actualDataset.content()
                .stream()
                .map(Dataset::id)
                .toList()).isEqualTo(expectedIdOrder);
    }

}