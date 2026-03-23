package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MinIOContainerUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.OptimizationResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.google.common.eventbus.EventBus;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Find project optimizations")
@ExtendWith(DropwizardAppExtensionProvider.class)
class OptimizationsResourceFindProjectOptimizationsTest {

    private static final String USER = UUID.randomUUID().toString();

    private static final String[] OPTIMIZATION_IGNORED_FIELDS = {"datasetId", "createdAt",
            "lastUpdatedAt", "createdBy", "lastUpdatedBy", "studioConfig", "datasetName",
            "baselineObjectiveScore", "bestObjectiveScore", "baselineDuration", "bestDuration",
            "baselineCost", "bestCost", "totalOptimizationCost", "experimentScores",
            "projectName"};

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final GenericContainer<?> MINIO = MinIOContainerUtils.newMinIOContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private OptimizationResourceClient optimizationResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private ProjectResourceClient projectResourceClient;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER_CONTAINER, MINIO).join();

        String minioUrl = "http://%s:%d".formatted(MINIO.getHost(), MINIO.getMappedPort(9000));

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);
        MinIOContainerUtils.setupBucketAndCredentials(minioUrl);

        APP = newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .authCacheTtlInSeconds(null)
                        .mockEventBus(Mockito.mock(EventBus.class))
                        .minioUrl(minioUrl)
                        .isMinIO(true)
                        .build());
    }

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.optimizationResourceClient = new OptimizationResourceClient(client, baseURI, factory);
        this.datasetResourceClient = new DatasetResourceClient(client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);

        ClientSupportUtils.config(client);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    private Dataset buildDataset() {
        return DatasetResourceClient.buildDataset(factory);
    }

    @Test
    @DisplayName("when getting optimizations by project, then return only that project's optimizations")
    void whenGettingOptimizationsByProjectId__thenReturnOnlyProjectOptimizations() {
        var apiKey = UUID.randomUUID().toString();
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectName = "project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

        var optimizations = List.of(
                optimizationResourceClient.createPartialOptimization().projectName(projectName).build(),
                optimizationResourceClient.createPartialOptimization().projectName(projectName).build());
        var ids = optimizations.stream()
                .map(o -> optimizationResourceClient.create(o, apiKey, workspaceName))
                .toList();
        var expected = List.of(
                optimizations.get(0).toBuilder().id(ids.get(0)).projectId(projectId).build(),
                optimizations.get(1).toBuilder().id(ids.get(1)).projectId(projectId).build());

        var otherProjectName = "project-" + UUID.randomUUID();
        projectResourceClient.createProject(otherProjectName, apiKey, workspaceName);
        optimizationResourceClient.create(
                optimizationResourceClient.createPartialOptimization().projectName(otherProjectName).build(),
                apiKey, workspaceName);

        var page = optimizationResourceClient.findByProject(
                projectId, apiKey, workspaceName, 1, 10, null, null, null, 200);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.content())
                .usingRecursiveComparison()
                .ignoringFields(OPTIMIZATION_IGNORED_FIELDS)
                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                .isEqualTo(expected.reversed());
    }

    @Test
    @DisplayName("when filtering by name, then return only matching optimizations")
    void whenFilteringByName__thenReturnMatchingOptimizations() {
        var apiKey = UUID.randomUUID().toString();
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectName = "project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

        var matchingName = "matching-" + UUID.randomUUID();
        var matched = optimizationResourceClient.createPartialOptimization()
                .name(matchingName).projectName(projectName).build();
        var matchedId = optimizationResourceClient.create(matched, apiKey, workspaceName);
        matched = matched.toBuilder().id(matchedId).projectId(projectId).build();

        optimizationResourceClient.create(
                optimizationResourceClient.createPartialOptimization().projectName(projectName).build(),
                apiKey, workspaceName);

        var page = optimizationResourceClient.findByProject(
                projectId, apiKey, workspaceName, 1, 10, null, matchingName, null, 200);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.content())
                .usingRecursiveComparison()
                .ignoringFields(OPTIMIZATION_IGNORED_FIELDS)
                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                .isEqualTo(List.of(matched));
    }

    @Test
    @DisplayName("when project has no optimizations, then return empty page")
    void whenProjectHasNoOptimizations__thenReturnEmptyPage() {
        var apiKey = UUID.randomUUID().toString();
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectName = "project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

        var page = optimizationResourceClient.findByProject(
                projectId, apiKey, workspaceName, 1, 10, null, null, null, 200);

        assertThat(page.total()).isEqualTo(0);
        assertThat(page.content()).isEmpty();
    }

    @Test
    @DisplayName("when filtering by datasetId, then return only optimizations with that dataset")
    void whenFilteringByDatasetId__thenReturnMatchingOptimizations() {
        var apiKey = UUID.randomUUID().toString();
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectName = "project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

        var dataset = buildDataset();
        datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

        var matched = optimizationResourceClient.createPartialOptimization()
                .datasetId(dataset.id()).datasetName(dataset.name()).projectName(projectName).build();
        var matchedId = optimizationResourceClient.create(matched, apiKey, workspaceName);
        matched = matched.toBuilder().id(matchedId).projectId(projectId).build();

        optimizationResourceClient.create(
                optimizationResourceClient.createPartialOptimization().projectName(projectName).build(),
                apiKey, workspaceName);

        var page = optimizationResourceClient.findByProject(
                projectId, apiKey, workspaceName, 1, 10, dataset.id(), null, null, 200);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.content())
                .usingRecursiveComparison()
                .ignoringFields(OPTIMIZATION_IGNORED_FIELDS)
                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                .isEqualTo(List.of(matched));
    }
}
