package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.OpikVersion;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.WorkspaceVersion;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AlertResourceClient;
import com.comet.opik.api.resources.utils.resources.AutomationRuleEvaluatorResourceClient;
import com.comet.opik.api.resources.utils.resources.DashboardResourceClient;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.OptimizationResourceClient;
import com.comet.opik.api.resources.utils.resources.PromptResourceClient;
import com.comet.opik.api.resources.utils.resources.WorkspaceResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestUtils.waitForMillis;
import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceVersionResourceTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();

    private static final WorkspaceVersion V1_WORKSPACE_VERSION = WorkspaceVersion.builder()
            .opikVersion(OpikVersion.VERSION_1)
            .build();
    private static final WorkspaceVersion V2_WORKSPACE_VERSION = WorkspaceVersion.builder()
            .opikVersion(OpikVersion.VERSION_2)
            .build();

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @ExtendWith(DropwizardAppExtensionProvider.class)
    class V2WorkspaceAllowlistTest {

        private static final String ALLOWLISTED_WORKSPACE_ID_1 = UUID.randomUUID().toString();
        private static final String ALLOWLISTED_WORKSPACE_ID_2 = UUID.randomUUID().toString();
        private static final String V2_WORKSPACE_ALLOWLIST = "%s, %s".formatted(
                ALLOWLISTED_WORKSPACE_ID_1, ALLOWLISTED_WORKSPACE_ID_2);

        private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
        private final Network NETWORK = Network.newNetwork();
        private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer(false, NETWORK);
        private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(
                false, NETWORK, ZOOKEEPER);
        private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer(false);

        @RegisterApp
        private final TestDropwizardAppExtension app;

        private final WireMockUtils.WireMockRuntime wireMock;

        {
            wireMock = WireMockUtils.startWireMock();

            Startables.deepStart(REDIS, MYSQL, CLICKHOUSE).join();

            MigrationUtils.runMysqlDbMigration(MYSQL);
            MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

            var databaseAnalyticsFactory = ClickHouseContainerUtils
                    .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

            app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                    AppContextConfig.builder()
                            .redisUrl(REDIS.getRedisURI())
                            .jdbcUrl(MYSQL.getJdbcUrl())
                            .databaseAnalyticsFactory(databaseAnalyticsFactory)
                            .runtimeInfo(wireMock.runtimeInfo())
                            .customConfigs(List.of(
                                    new CustomConfig("serviceToggles.v2WorkspaceAllowlist", V2_WORKSPACE_ALLOWLIST),
                                    new CustomConfig("serviceToggles.forceWorkspaceVersion", "version_1")))
                            .build());
        }

        private WorkspaceResourceClient workspaceClient;

        @BeforeAll
        void beforeAll(ClientSupport clientSupport) {
            var baseUrl = TestUtils.getBaseUrl(clientSupport);
            ClientSupportUtils.config(clientSupport);
            workspaceClient = new WorkspaceResourceClient(clientSupport, baseUrl, podamFactory);
        }

        @AfterAll
        void afterAll() {
            wireMock.server().stop();
        }

        static Stream<Arguments> workspaceVersion__whenAllowlistAndForceV1__returnsExpectedVersion() {
            return Stream.of(
                    arguments(ALLOWLISTED_WORKSPACE_ID_1, V2_WORKSPACE_VERSION),
                    arguments(ALLOWLISTED_WORKSPACE_ID_2, V2_WORKSPACE_VERSION),
                    arguments(UUID.randomUUID().toString(), V1_WORKSPACE_VERSION));
        }

        @ParameterizedTest
        @MethodSource
        void workspaceVersion__whenAllowlistAndForceV1__returnsExpectedVersion(
                String workspaceId, WorkspaceVersion expectedVersion) {
            var workspaceName = mockWorkspace(wireMock, workspaceId, null);

            // If workspace ID in allow list will return V2, otherwise forcing to return V1
            assertThat(workspaceClient.getWorkspaceVersion(API_KEY, workspaceName)).isEqualTo(expectedVersion);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @ExtendWith(DropwizardAppExtensionProvider.class)
    class ForceWorkspaceVersion1Test {

        private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
        private final Network NETWORK = Network.newNetwork();
        private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer(false, NETWORK);
        private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(
                false, NETWORK, ZOOKEEPER);
        private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer(false);

        @RegisterApp
        private final TestDropwizardAppExtension app;

        {
            Startables.deepStart(REDIS, CLICKHOUSE, MYSQL).join();

            var databaseAnalyticsFactory = ClickHouseContainerUtils
                    .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

            MigrationUtils.runMysqlDbMigration(MYSQL);
            MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

            app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                    AppContextConfig.builder()
                            .jdbcUrl(MYSQL.getJdbcUrl())
                            .databaseAnalyticsFactory(databaseAnalyticsFactory)
                            .redisUrl(REDIS.getRedisURI())
                            .customConfigs(List.of(
                                    new CustomConfig("serviceToggles.forceWorkspaceVersion", "version_1")))
                            .build());
        }

        private WorkspaceResourceClient workspaceClient;

        @BeforeAll
        void beforeAll(ClientSupport clientSupport) {
            var baseUrl = TestUtils.getBaseUrl(clientSupport);
            ClientSupportUtils.config(clientSupport);
            workspaceClient = new WorkspaceResourceClient(clientSupport, baseUrl, podamFactory);
        }

        @Test
        void workspaceVersion__whenForceVersion1__returnsVersion1() {
            // Empty workspace should point to V2, but the flag forces V1
            assertThat(workspaceClient.getWorkspaceVersion(DEFAULT_WORKSPACE_NAME)).isEqualTo(V1_WORKSPACE_VERSION);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @ExtendWith(DropwizardAppExtensionProvider.class)
    class ForceWorkspaceVersion2Test {

        private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
        private final Network NETWORK = Network.newNetwork();
        private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer(false, NETWORK);
        private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(
                false, NETWORK, ZOOKEEPER);
        private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer(false);

        @RegisterApp
        private final TestDropwizardAppExtension app;

        {
            Startables.deepStart(REDIS, CLICKHOUSE, MYSQL).join();

            var databaseAnalyticsFactory = ClickHouseContainerUtils
                    .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

            MigrationUtils.runMysqlDbMigration(MYSQL);
            MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

            app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                    AppContextConfig.builder()
                            .jdbcUrl(MYSQL.getJdbcUrl())
                            .databaseAnalyticsFactory(databaseAnalyticsFactory)
                            .redisUrl(REDIS.getRedisURI())
                            .customConfigs(List.of(
                                    new CustomConfig("serviceToggles.forceWorkspaceVersion", "version_2")))
                            .build());
        }

        private WorkspaceResourceClient workspaceClient;
        private DatasetResourceClient datasetClient;

        @BeforeAll
        void beforeAll(ClientSupport clientSupport) {
            var baseUrl = TestUtils.getBaseUrl(clientSupport);
            ClientSupportUtils.config(clientSupport);
            workspaceClient = new WorkspaceResourceClient(clientSupport, baseUrl, podamFactory);
            datasetClient = new DatasetResourceClient(clientSupport, baseUrl);
        }

        @Test
        void workspaceVersion__whenForceVersion2__returnsVersion2() {
            // Creating V1 dataset
            datasetClient.createDataset(podamFactory.manufacturePojo(Dataset.class).toBuilder()
                    .projectId(null)
                    .projectName(null)
                    .build(),
                    API_KEY, DEFAULT_WORKSPACE_NAME);

            // It should have been V1, but the flag forces V2
            assertThat(workspaceClient.getWorkspaceVersion(DEFAULT_WORKSPACE_NAME)).isEqualTo(V2_WORKSPACE_VERSION);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @ExtendWith(DropwizardAppExtensionProvider.class)
    class EntityWorkspaceVersionTest {

        private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
        private final Network NETWORK = Network.newNetwork();
        private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer(false);
        private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer(false, NETWORK);
        private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(
                false, NETWORK, ZOOKEEPER);

        @RegisterApp
        private final TestDropwizardAppExtension app;

        private final WireMockUtils.WireMockRuntime wireMock;

        {
            wireMock = WireMockUtils.startWireMock();

            Startables.deepStart(REDIS, MYSQL, CLICKHOUSE).join();

            MigrationUtils.runMysqlDbMigration(MYSQL);
            MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

            var databaseAnalyticsFactory = ClickHouseContainerUtils
                    .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

            app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                    AppContextConfig.builder()
                            .redisUrl(REDIS.getRedisURI())
                            .jdbcUrl(MYSQL.getJdbcUrl())
                            .databaseAnalyticsFactory(databaseAnalyticsFactory)
                            .runtimeInfo(wireMock.runtimeInfo())
                            .build());
        }

        private WorkspaceResourceClient workspaceClient;
        private DatasetResourceClient datasetClient;
        private PromptResourceClient promptClient;
        private DashboardResourceClient dashboardClient;
        private AutomationRuleEvaluatorResourceClient evaluatorClient;
        private ExperimentResourceClient experimentClient;
        private OptimizationResourceClient optimizationClient;
        private AlertResourceClient alertClient;

        @BeforeAll
        void beforeAll(ClientSupport clientSupport) {
            var baseUrl = TestUtils.getBaseUrl(clientSupport);
            ClientSupportUtils.config(clientSupport);
            workspaceClient = new WorkspaceResourceClient(clientSupport, baseUrl, podamFactory);
            datasetClient = new DatasetResourceClient(clientSupport, baseUrl);
            promptClient = new PromptResourceClient(clientSupport, baseUrl, podamFactory);
            dashboardClient = new DashboardResourceClient(clientSupport, baseUrl);
            evaluatorClient = new AutomationRuleEvaluatorResourceClient(clientSupport, baseUrl);
            experimentClient = new ExperimentResourceClient(clientSupport, baseUrl, podamFactory);
            optimizationClient = new OptimizationResourceClient(clientSupport, baseUrl, podamFactory);
            alertClient = new AlertResourceClient(clientSupport);
        }

        @AfterAll
        void afterAll() {
            wireMock.server().stop();
        }

        @Test
        void workspaceVersion__whenAuthSaysVersion2__locksToVersion2() {
            var workspaceName = mockWorkspace(wireMock, OpikVersion.VERSION_2);

            // Creating a V1 entity
            datasetClient.createDataset(podamFactory.manufacturePojo(Dataset.class).toBuilder()
                    .projectId(null)
                    .projectName(null)
                    .build(), API_KEY, workspaceName);
            // Auth says version_2 — one-way gate locks to V2 even with v1 entities
            assertThat(workspaceClient.getWorkspaceVersion(API_KEY, workspaceName)).isEqualTo(V2_WORKSPACE_VERSION);
        }

        @Test
        void workspaceVersion__whenDatasetEntities__returnsExpectedVersion() {
            // When Auth says Version1 entity check still runs
            var workspaceName = mockWorkspace(wireMock, OpikVersion.VERSION_1);

            // Demo-only datasets do not trigger version_1
            datasetClient.createDataset(podamFactory.manufacturePojo(Dataset.class).toBuilder()
                    .name("Demo dataset")
                    .projectId(null)
                    .projectName(null)
                    .build(),
                    API_KEY, workspaceName);
            // Dataset under project does not trigger version_1
            datasetClient.createDataset(podamFactory.manufacturePojo(Dataset.class).toBuilder()
                    .projectId(null)
                    .build(),
                    API_KEY, workspaceName);
            // Auth says version_1 — not a one-way gate, project scoped workspace still returns V2
            assertThat(workspaceClient.getWorkspaceVersion(API_KEY, workspaceName)).isEqualTo(V2_WORKSPACE_VERSION);

            // Version 1 dataset triggers version_1
            datasetClient.createDataset(podamFactory.manufacturePojo(Dataset.class).toBuilder()
                    .projectId(null)
                    .projectName(null)
                    .build(), API_KEY, workspaceName);
            assertThat(workspaceClient.getWorkspaceVersion(API_KEY, workspaceName)).isEqualTo(V1_WORKSPACE_VERSION);
        }

        @Test
        void workspaceVersion__whenPromptWithoutProject__returnsVersion1() {
            // Auth doesn't include opikVersion — defensive, entity check is primary signal
            var workspaceName = mockWorkspace(wireMock);

            // Empty workspace returns version_2
            assertThat(workspaceClient.getWorkspaceVersion(API_KEY, workspaceName)).isEqualTo(V2_WORKSPACE_VERSION);

            // Version 1 prompt triggers version_1
            promptClient.createPrompt(podamFactory.manufacturePojo(Prompt.class).toBuilder()
                    .projectId(null)
                    .projectName(null)
                    .build(),
                    API_KEY, workspaceName);
            assertThat(workspaceClient.getWorkspaceVersion(API_KEY, workspaceName)).isEqualTo(V1_WORKSPACE_VERSION);
        }

        @Test
        void workspaceVersion__whenDashboardWithoutProject__returnsVersion1() {
            var workspaceName = mockWorkspace(wireMock);

            // Empty workspace returns version_2
            assertThat(workspaceClient.getWorkspaceVersion(API_KEY, workspaceName)).isEqualTo(V2_WORKSPACE_VERSION);

            // Version 1 dashboard triggers version_1
            dashboardClient.create(dashboardClient.createPartialDashboard().build(), API_KEY, workspaceName);
            assertThat(workspaceClient.getWorkspaceVersion(API_KEY, workspaceName)).isEqualTo(V1_WORKSPACE_VERSION);
        }

        @Test
        void workspaceVersion__whenMultiProjectRule__returnsVersion1() {
            var workspaceName = mockWorkspace(wireMock);

            // Single-project rule does not trigger version_1
            evaluatorClient.createEvaluator(podamFactory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .projectIds(Set.of(UUID.randomUUID()))
                    .build(),
                    workspaceName, API_KEY);
            assertThat(workspaceClient.getWorkspaceVersion(API_KEY, workspaceName)).isEqualTo(V2_WORKSPACE_VERSION);

            // Multi-project rule triggers version_1
            evaluatorClient.createEvaluator(podamFactory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .projectIds(Set.of(UUID.randomUUID(), UUID.randomUUID()))
                    .build(),
                    workspaceName, API_KEY);
            assertThat(workspaceClient.getWorkspaceVersion(API_KEY, workspaceName)).isEqualTo(V1_WORKSPACE_VERSION);
        }

        @Test
        void workspaceVersion__whenExperimentWithoutProject__returnsVersion1() {
            var workspaceName = mockWorkspace(wireMock);

            // Project-scoped dataset for the experiment does not trigger V1
            var dataset = podamFactory.manufacturePojo(Dataset.class).toBuilder()
                    .projectId(null)
                    .build();
            datasetClient.createDataset(dataset, API_KEY, workspaceName);
            // Demo experiment without project does not trigger V1
            experimentClient.create(experimentClient.createPartialExperiment()
                    .name("Demo evaluation")
                    .datasetName(dataset.name())
                    .build(),
                    API_KEY, workspaceName);
            assertThat(workspaceClient.getWorkspaceVersion(API_KEY, workspaceName)).isEqualTo(V2_WORKSPACE_VERSION);

            // Non-demo experiment without project triggers V1
            experimentClient.create(experimentClient.createPartialExperiment()
                    .datasetName(dataset.name())
                    .build(),
                    API_KEY, workspaceName);
            assertThat(workspaceClient.getWorkspaceVersion(API_KEY, workspaceName)).isEqualTo(V1_WORKSPACE_VERSION);
        }

        @Test
        void workspaceVersion__whenOptimizationWithoutProject__returnsVersion1() {
            var workspaceName = mockWorkspace(wireMock);

            // Project-scoped dataset for the optimization does not trigger V1
            var dataset = podamFactory.manufacturePojo(Dataset.class).toBuilder()
                    .projectId(null)
                    .build();
            datasetClient.createDataset(dataset, API_KEY, workspaceName);
            assertThat(workspaceClient.getWorkspaceVersion(API_KEY, workspaceName)).isEqualTo(V2_WORKSPACE_VERSION);

            // Workspace level optimization triggers V1
            optimizationClient.create(optimizationClient.createPartialOptimization()
                    .datasetName(dataset.name())
                    .build(),
                    API_KEY, workspaceName);
            assertThat(workspaceClient.getWorkspaceVersion(API_KEY, workspaceName)).isEqualTo(V1_WORKSPACE_VERSION);
        }

        @Test
        void workspaceVersion__whenAlertWithoutProject__returnsVersion1() {
            var workspaceName = mockWorkspace(wireMock);

            // Project-scoped alert (projectId column) does not trigger version_1
            alertClient.createAlert(
                    AlertResourceTest.generateAlertForProject(podamFactory, UUID.randomUUID()),
                    API_KEY, workspaceName, 201);
            assertThat(workspaceClient.getWorkspaceVersion(API_KEY, workspaceName)).isEqualTo(V2_WORKSPACE_VERSION);

            // Workspace level alert triggers version_1
            alertClient.createAlert(
                    AlertResourceTest.generateAlert(podamFactory),
                    API_KEY, workspaceName, 201);
            assertThat(workspaceClient.getWorkspaceVersion(API_KEY, workspaceName)).isEqualTo(V1_WORKSPACE_VERSION);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @ExtendWith(DropwizardAppExtensionProvider.class)
    class CacheWorkspaceVersionTest {

        private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
        private final Network NETWORK = Network.newNetwork();
        private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer(false, NETWORK);
        private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(
                false, NETWORK, ZOOKEEPER);
        private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer(false);

        @RegisterApp
        private final TestDropwizardAppExtension app;

        {
            Startables.deepStart(REDIS, CLICKHOUSE, MYSQL).join();

            var databaseAnalyticsFactory = ClickHouseContainerUtils
                    .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

            MigrationUtils.runMysqlDbMigration(MYSQL);
            MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

            app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                    AppContextConfig.builder()
                            .jdbcUrl(MYSQL.getJdbcUrl())
                            .databaseAnalyticsFactory(databaseAnalyticsFactory)
                            .redisUrl(REDIS.getRedisURI())
                            .customConfigs(List.of(
                                    new CustomConfig("cacheManager.enabled", "true"),
                                    new CustomConfig("cacheManager.caches.workspace_version", "PT1S")))
                            .build());
        }

        private WorkspaceResourceClient workspaceClient;
        private DatasetResourceClient datasetClient;

        @BeforeAll
        void beforeAll(ClientSupport clientSupport) {
            var baseUrl = TestUtils.getBaseUrl(clientSupport);
            ClientSupportUtils.config(clientSupport);
            workspaceClient = new WorkspaceResourceClient(clientSupport, baseUrl, podamFactory);
            datasetClient = new DatasetResourceClient(clientSupport, baseUrl);
        }

        @Test
        void workspaceVersion__whenCacheEnabled__computesAndCachesWithTtl() {
            // First call returns version_2 on empty workspace and caches it
            var response1 = workspaceClient.getWorkspaceVersion(DEFAULT_WORKSPACE_NAME);
            assertThat(response1.opikVersion()).isEqualTo(OpikVersion.VERSION_2);

            // Creating V1 dataset
            datasetClient.createDataset(podamFactory.manufacturePojo(Dataset.class).toBuilder()
                    .projectId(null)
                    .projectName(null)
                    .build(),
                    API_KEY, DEFAULT_WORKSPACE_NAME);
            // Cached version_2 returned even after creating version 1 entity
            var response2 = workspaceClient.getWorkspaceVersion(DEFAULT_WORKSPACE_NAME);
            assertThat(response2.opikVersion()).isEqualTo(OpikVersion.VERSION_2);

            // After cache TTL expires, entity check runs and returns version_1
            waitForMillis(1500);
            var response3 = workspaceClient.getWorkspaceVersion(DEFAULT_WORKSPACE_NAME);
            assertThat(response3.opikVersion()).isEqualTo(OpikVersion.VERSION_1);
        }
    }

    private static String mockWorkspace(WireMockUtils.WireMockRuntime wireMock) {
        return mockWorkspace(wireMock, null);
    }

    private static String mockWorkspace(WireMockUtils.WireMockRuntime wireMock, OpikVersion opikVersion) {
        var workspaceId = UUID.randomUUID().toString();
        return mockWorkspace(wireMock, workspaceId, opikVersion);
    }

    private static String mockWorkspace(
            WireMockUtils.WireMockRuntime wireMock, String workspaceId, OpikVersion opikVersion) {
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var user = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);
        AuthTestUtils.mockTargetWorkspace(
                wireMock.server(), API_KEY, workspaceName, workspaceId, user, null, opikVersion);
        return workspaceName;
    }
}
