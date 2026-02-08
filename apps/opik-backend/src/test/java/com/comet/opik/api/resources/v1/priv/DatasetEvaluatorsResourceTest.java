package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetEvaluator;
import com.comet.opik.api.DatasetEvaluator.DatasetEvaluatorBatchRequest;
import com.comet.opik.api.DatasetEvaluator.DatasetEvaluatorCreate;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetEvaluatorResourceClient;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetEvaluatorsResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE_NAME = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);

        var contextConfig = TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(REDIS.getRedisURI())
                .authCacheTtlInSeconds(null)
                .build();

        APP = newTestDropwizardAppExtension(contextConfig);
    }

    private String baseURI;
    private DatasetEvaluatorResourceClient datasetEvaluatorClient;
    private DatasetResourceClient datasetClient;

    @BeforeAll
    void beforeAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);

        ClientSupportUtils.config(client);

        this.datasetEvaluatorClient = new DatasetEvaluatorResourceClient(client, baseURI);
        this.datasetClient = new DatasetResourceClient(client, baseURI);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE_NAME, WORKSPACE_ID, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private UUID createDataset() {
        var dataset = Dataset.builder()
                .name("test-dataset-" + UUID.randomUUID())
                .build();
        return datasetClient.createDataset(dataset, API_KEY, TEST_WORKSPACE_NAME);
    }

    @Nested
    @DisplayName("Create dataset evaluators")
    class CreateDatasetEvaluators {

        @Test
        @DisplayName("Create dataset evaluators in batch")
        void createDatasetEvaluatorsBatch() {
            var datasetId = createDataset();

            var request = datasetEvaluatorClient.createBatchRequest(3);

            var created = datasetEvaluatorClient.createBatch(datasetId, request, API_KEY, TEST_WORKSPACE_NAME);

            assertThat(created).hasSize(3);
            for (int i = 0; i < 3; i++) {
                var evaluator = created.get(i);
                assertThat(evaluator.id()).isNotNull();
                assertThat(evaluator.datasetId()).isEqualTo(datasetId);
                assertThat(evaluator.createdBy()).isEqualTo(USER);
                assertThat(evaluator.lastUpdatedBy()).isEqualTo(USER);
                assertThat(evaluator.createdAt()).isNotNull();
                assertThat(evaluator.lastUpdatedAt()).isNotNull();
            }
        }

        @Test
        @DisplayName("Create dataset evaluator with custom config")
        void createDatasetEvaluatorWithCustomConfig() {
            var datasetId = createDataset();

            var evaluatorCreate = DatasetEvaluatorCreate.builder()
                    .name("hallucination-check")
                    .metricType("hallucination")
                    .metricConfig(datasetEvaluatorClient.createValidMetricConfig())
                    .build();

            var request = DatasetEvaluatorBatchRequest.builder()
                    .evaluators(List.of(evaluatorCreate))
                    .build();

            var created = datasetEvaluatorClient.createBatch(datasetId, request, API_KEY, TEST_WORKSPACE_NAME);

            assertThat(created).hasSize(1);
            var evaluator = created.get(0);
            assertThat(evaluator.name()).isEqualTo("hallucination-check");
            assertThat(evaluator.metricType()).isEqualTo("hallucination");
            assertThat(evaluator.metricConfig()).isNotNull();
            assertThat(evaluator.metricConfig().get("threshold").asDouble()).isEqualTo(0.8);
        }
    }

    @Nested
    @DisplayName("Get dataset evaluators")
    class GetDatasetEvaluators {

        @Test
        @DisplayName("Get dataset evaluators with pagination")
        void getDatasetEvaluatorsWithPagination() {
            var datasetId = createDataset();

            var request = datasetEvaluatorClient.createBatchRequest(5);
            datasetEvaluatorClient.createBatch(datasetId, request, API_KEY, TEST_WORKSPACE_NAME);

            var page1 = datasetEvaluatorClient.get(datasetId, API_KEY, TEST_WORKSPACE_NAME, 1, 2);
            assertThat(page1.content()).hasSize(2);
            assertThat(page1.total()).isEqualTo(5);
            assertThat(page1.page()).isEqualTo(0);

            var page2 = datasetEvaluatorClient.get(datasetId, API_KEY, TEST_WORKSPACE_NAME, 2, 2);
            assertThat(page2.content()).hasSize(2);
            assertThat(page2.total()).isEqualTo(5);
            assertThat(page2.page()).isEqualTo(1);

            var page3 = datasetEvaluatorClient.get(datasetId, API_KEY, TEST_WORKSPACE_NAME, 3, 2);
            assertThat(page3.content()).hasSize(1);
            assertThat(page3.total()).isEqualTo(5);
        }

        @Test
        @DisplayName("Get dataset evaluators returns empty page for dataset without evaluators")
        void getDatasetEvaluatorsReturnsEmptyPage() {
            var datasetId = createDataset();

            var page = datasetEvaluatorClient.get(datasetId, API_KEY, TEST_WORKSPACE_NAME, 1, 10);

            assertThat(page.content()).isEmpty();
            assertThat(page.total()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Delete dataset evaluators")
    class DeleteDatasetEvaluators {

        @Test
        @DisplayName("Delete dataset evaluators in batch")
        void deleteDatasetEvaluatorsBatch() {
            var datasetId = createDataset();

            var request = datasetEvaluatorClient.createBatchRequest(3);
            var created = datasetEvaluatorClient.createBatch(datasetId, request, API_KEY, TEST_WORKSPACE_NAME);

            var idsToDelete = created.stream()
                    .limit(2)
                    .map(DatasetEvaluator::id)
                    .collect(Collectors.toSet());

            datasetEvaluatorClient.deleteBatch(datasetId, idsToDelete, API_KEY, TEST_WORKSPACE_NAME);

            var remaining = datasetEvaluatorClient.get(datasetId, API_KEY, TEST_WORKSPACE_NAME, 1, 10);
            assertThat(remaining.content()).hasSize(1);
            assertThat(remaining.content().get(0).id()).isEqualTo(created.get(2).id());
        }

        @Test
        @DisplayName("Delete all dataset evaluators")
        void deleteAllDatasetEvaluators() {
            var datasetId = createDataset();

            var request = datasetEvaluatorClient.createBatchRequest(3);
            var created = datasetEvaluatorClient.createBatch(datasetId, request, API_KEY, TEST_WORKSPACE_NAME);

            var allIds = created.stream()
                    .map(DatasetEvaluator::id)
                    .collect(Collectors.toSet());

            datasetEvaluatorClient.deleteBatch(datasetId, allIds, API_KEY, TEST_WORKSPACE_NAME);

            var remaining = datasetEvaluatorClient.get(datasetId, API_KEY, TEST_WORKSPACE_NAME, 1, 10);
            assertThat(remaining.content()).isEmpty();
            assertThat(remaining.total()).isEqualTo(0);
        }

        @Test
        @DisplayName("Delete with non-existent IDs returns 400")
        void deleteWithNonExistentIdsReturns400() {
            var datasetId = createDataset();

            Set<UUID> nonExistentIds = Set.of(UUID.randomUUID(), UUID.randomUUID());

            datasetEvaluatorClient.deleteBatch(datasetId, nonExistentIds, API_KEY, TEST_WORKSPACE_NAME,
                    HttpStatus.SC_BAD_REQUEST);
        }

        @Test
        @DisplayName("Delete with IDs from another dataset returns 400")
        void deleteWithIdsFromAnotherDatasetReturns400() {
            var datasetId1 = createDataset();
            var datasetId2 = createDataset();

            var request = datasetEvaluatorClient.createBatchRequest(2);
            var createdInDataset1 = datasetEvaluatorClient.createBatch(datasetId1, request, API_KEY,
                    TEST_WORKSPACE_NAME);

            var idsFromDataset1 = createdInDataset1.stream()
                    .map(DatasetEvaluator::id)
                    .collect(Collectors.toSet());

            datasetEvaluatorClient.deleteBatch(datasetId2, idsFromDataset1, API_KEY, TEST_WORKSPACE_NAME,
                    HttpStatus.SC_BAD_REQUEST);

            var remainingInDataset1 = datasetEvaluatorClient.get(datasetId1, API_KEY, TEST_WORKSPACE_NAME, 1, 10);
            assertThat(remainingInDataset1.content()).hasSize(2);
        }

        @Test
        @DisplayName("Delete with mixed valid and invalid IDs returns 400 and does not delete any")
        void deleteWithMixedValidAndInvalidIdsReturns400() {
            var datasetId = createDataset();

            var request = datasetEvaluatorClient.createBatchRequest(2);
            var created = datasetEvaluatorClient.createBatch(datasetId, request, API_KEY, TEST_WORKSPACE_NAME);

            Set<UUID> mixedIds = new HashSet<>();
            mixedIds.add(created.get(0).id());
            mixedIds.add(UUID.randomUUID());

            datasetEvaluatorClient.deleteBatch(datasetId, mixedIds, API_KEY, TEST_WORKSPACE_NAME,
                    HttpStatus.SC_BAD_REQUEST);

            var remaining = datasetEvaluatorClient.get(datasetId, API_KEY, TEST_WORKSPACE_NAME, 1, 10);
            assertThat(remaining.content()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Workspace isolation")
    class WorkspaceIsolation {

        @Test
        @DisplayName("Evaluators from one workspace are not visible in another")
        void evaluatorsFromOneWorkspaceNotVisibleInAnother() {
            var datasetId = createDataset();

            var request = datasetEvaluatorClient.createBatchRequest(2);
            datasetEvaluatorClient.createBatch(datasetId, request, API_KEY, TEST_WORKSPACE_NAME);

            var otherApiKey = UUID.randomUUID().toString();
            var otherWorkspaceId = UUID.randomUUID().toString();
            var otherWorkspaceName = "other-workspace-" + UUID.randomUUID();
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), otherApiKey, otherWorkspaceName, otherWorkspaceId,
                    USER);

            var page = datasetEvaluatorClient.get(datasetId, otherApiKey, otherWorkspaceName, 1, 10);
            assertThat(page.content()).isEmpty();
            assertThat(page.total()).isEqualTo(0);
        }
    }
}
