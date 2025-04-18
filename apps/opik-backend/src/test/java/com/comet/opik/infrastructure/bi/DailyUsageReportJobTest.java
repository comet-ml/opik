package com.comet.opik.infrastructure.bi;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.DemoData;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JobManagerUtils;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamUtils;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DailyUsageReportJobTest {

    public static final String SUCCESS_RESPONSE = "{\"message\":\"Event added successfully\",\"success\":\"true\"}";

    private static final String USER = UUID.randomUUID().toString();

    private static final String VERSION = "%s.%s.%s".formatted(PodamUtils.getIntegerInRange(1, 99),
            PodamUtils.getIntegerInRange(1, 99), PodamUtils.getIntegerInRange(1, 99));

    private void runMigrations(MySQLContainer<?> mysql, ClickHouseContainer clickhouse) {
        try {
            MigrationUtils.runDbMigration(mysql.createConnection(""),
                    MySQLContainerUtils.migrationParameters());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        try (var connection = clickhouse.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void mockBiEventResponse(String eventType, WireMockServer server) {
        server.stubFor(
                post(urlPathEqualTo("/v1/notify/event"))
                        .withRequestBody(matchingJsonPath("$.anonymous_id", matching(
                                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")))
                        .withRequestBody(matchingJsonPath("$.event_type",
                                matching(eventType)))
                        .withRequestBody(matchingJsonPath("$.event_properties.opik_app_version", matching(VERSION)))
                        .willReturn(WireMock.okJson(SUCCESS_RESPONSE)));
    }

    private void verifyResponse(WireMockServer server, String totalUsers, String dailyUsers) {
        server.verify(
                postRequestedFor(urlPathEqualTo("/v1/notify/event"))
                        .withRequestBody(matchingJsonPath("$.anonymous_id", matching(
                                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))
                                .and(matchingJsonPath("$.event_type",
                                        equalTo(DailyUsageReportJob.STATISTICS_BE)))
                                .and(matchingJsonPath("$.event_properties.total_users", equalTo(totalUsers)))
                                .and(matchingJsonPath("$.event_properties.opik_app_version",
                                        equalTo(VERSION)))
                                .and(matchingJsonPath("$.event_properties.daily_users", equalTo(dailyUsers)))
                                .and(matchingJsonPath("$.event_properties.daily_traces", equalTo("5")))
                                .and(matchingJsonPath("$.event_properties.daily_experiments", equalTo("5")))
                                .and(matchingJsonPath("$.event_properties.daily_datasets", equalTo("5")))));
    }

    private void updateDatasets(String workspaceId, TransactionTemplate transactionTemplate, boolean updateUser) {
        transactionTemplate
                .inTransaction(TransactionTemplateAsync.WRITE, handle -> handle.createUpdate("""
                            UPDATE datasets
                            SET created_at = now() - INTERVAL 1 DAY,
                                last_updated_at = now() - INTERVAL 1 DAY,
                                last_updated_by = if(:update_user, id, last_updated_by),
                                created_by = if(:update_user, id, created_by)
                            WHERE workspace_id = :workspace_id
                        """)
                        .bind("workspace_id", workspaceId)
                        .bind("update_user", updateUser)
                        .execute());
    }

    private void updateExperiments(String workspaceId, TransactionTemplateAsync templateAsync, boolean updateUser) {
        String sql = """
                INSERT INTO experiments (
                    id,
                    dataset_id,
                    name,
                    workspace_id,
                    metadata,
                    created_by,
                    last_updated_by,
                    created_at,
                    last_updated_at
                )
                SELECT
                    toString(generateUUIDv4()),
                    dataset_id,
                    name,
                    workspace_id,
                    metadata,
                    if(:update_user, toString(generateUUIDv4()), created_by),
                    if(:update_user, toString(generateUUIDv4()), last_updated_by),
                    created_at - INTERVAL 1 DAY,
                    last_updated_at - INTERVAL 1 DAY
                FROM experiments
                WHERE workspace_id = :workspace_id
                ;
                """;

        templateAsync.nonTransaction(connection -> Mono.from(connection.createStatement(sql)
                .bind("workspace_id", workspaceId)
                .bind("update_user", updateUser)
                .execute()))
                .block();
    }

    private void updateTraces(String workspaceId, TransactionTemplateAsync templateAsync, boolean updateUser) {

        String sql = """
                INSERT INTO traces (
                    id,
                    project_id,
                    workspace_id,
                    name,
                    start_time,
                    end_time,
                    input,
                    output,
                    metadata,
                    tags,
                    created_at,
                    last_updated_at,
                    created_by,
                    last_updated_by
                )
                SELECT
                    toString(generateUUIDv4()),
                    project_id,
                    workspace_id,
                    name,
                    start_time - INTERVAL 1 DAY,
                    end_time - INTERVAL 1 DAY,
                    input,
                    output,
                    metadata,
                    tags,
                    created_at - INTERVAL 1 DAY,
                    last_updated_at - INTERVAL 1 DAY,
                    if(:update_user, toString(generateUUIDv4()), created_by),
                    if(:update_user, toString(generateUUIDv4()), last_updated_by)
                FROM traces
                WHERE workspace_id = :workspace_id
                ;
                """;
        templateAsync.nonTransaction(connection -> Mono.from(connection.createStatement(sql)
                .bind("workspace_id", workspaceId)
                .bind("update_user", updateUser)
                .execute())).block();
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @ExtendWith(DropwizardAppExtensionProvider.class)
    class CredentialsEnabledScenario {

        private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
        private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer(false);
        private final Network NETWORK = Network.newNetwork();
        private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer(false,
                NETWORK);
        private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(false, NETWORK,
                ZOOKEEPER_CONTAINER);

        private final WireMockUtils.WireMockRuntime wireMock;

        @RegisterApp
        private final TestDropwizardAppExtension APP;

        {
            Startables.deepStart(REDIS, MYSQL, CLICKHOUSE).join();

            wireMock = WireMockUtils.startWireMock();

            var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                    CLICKHOUSE, DATABASE_NAME);

            mockBiEventResponse(DailyUsageReportJob.STATISTICS_BE, wireMock.server());

            mockBiEventResponse(InstallationReportService.NOTIFICATION_EVENT_TYPE, wireMock.server());

            runMigrations(MYSQL, CLICKHOUSE);

            APP = newTestDropwizardAppExtension(
                    AppContextConfig.builder()
                            .jdbcUrl(MYSQL.getJdbcUrl())
                            .databaseAnalyticsFactory(databaseAnalyticsFactory)
                            .redisUrl(REDIS.getRedisURI())
                            .runtimeInfo(wireMock.runtimeInfo())
                            .usageReportUrl("%s/v1/notify/event".formatted(wireMock.runtimeInfo().getHttpBaseUrl()))
                            .usageReportEnabled(true)
                            .metadataVersion(VERSION)
                            .build());
        }

        private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

        private String baseURI;
        private ClientSupport client;
        private ExperimentResourceClient experimentResourceClient;
        private TraceResourceClient traceResourceClient;
        private DatasetResourceClient datasetResourceClient;
        private ProjectResourceClient projectResourceClient;
        private TransactionTemplateAsync templateAsync;
        private TransactionTemplate transactionTemplate;

        @BeforeAll
        void setUpAll(ClientSupport client, TransactionTemplate transactionTemplate,
                TransactionTemplateAsync templateAsync) {

            this.baseURI = "http://localhost:%d".formatted(client.getPort());
            this.client = client;
            this.templateAsync = templateAsync;
            this.transactionTemplate = transactionTemplate;

            ClientSupportUtils.config(client);

            experimentResourceClient = new ExperimentResourceClient(this.client, baseURI, factory);
            traceResourceClient = new TraceResourceClient(this.client, baseURI);
            datasetResourceClient = new DatasetResourceClient(this.client, baseURI);
            projectResourceClient = new ProjectResourceClient(this.client, baseURI, factory);
        }

        @AfterAll
        void tearDownAll() {
            wireMock.server().stop();
            MYSQL.stop();
            CLICKHOUSE.stop();
            ZOOKEEPER_CONTAINER.stop();
            NETWORK.close();
            ZOOKEEPER_CONTAINER.stop();
        }

        private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
        }

        @Test
        void test() throws SchedulerException {

            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            projectResourceClient.createProject(UUID.randomUUID().toString(), apiKey, workspaceName);

            setUpData(apiKey, workspaceName, workspaceId);

            var key = JobKey.jobKey(DailyUsageReportJob.class.getName());

            var trigger = TriggerBuilder.newTrigger().startNow().forJob(key).build();

            JobManagerUtils.getJobManager().getScheduler().scheduleJob(trigger);

            Awaitility
                    .await()
                    .atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        verifyResponse(wireMock.server(), "17", "15");
                    });

        }

        private void setUpData(String apiKey, String workspaceName, String workspaceId) {
            List<Dataset> datasets = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class);

            datasets.parallelStream().forEach(dataset -> {
                datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
            });

            List<Experiment> experiments = datasets
                    .stream()
                    .map(dataset -> experimentResourceClient.createPartialExperiment()
                            .datasetId(dataset.id())
                            .datasetName(dataset.name())
                            .build())
                    .toList();

            experiments.parallelStream().forEach(experiment -> {
                experimentResourceClient.create(experiment, apiKey, workspaceName);
            });

            List<Trace> traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class);

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            updateDatasets(workspaceId, transactionTemplate, true);
            updateExperiments(workspaceId, templateAsync, true);
            updateTraces(workspaceId, templateAsync, true);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @ExtendWith(DropwizardAppExtensionProvider.class)
    class NoCredentialsEnabledScenario {

        private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
        private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer(false);
        private final Network NETWORK = Network.newNetwork();
        private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer(false,
                NETWORK);
        private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(false, NETWORK,
                ZOOKEEPER_CONTAINER);

        private final WireMockUtils.WireMockRuntime wireMock;

        @RegisterApp
        private final TestDropwizardAppExtension APP;

        {
            Startables.deepStart(REDIS, MYSQL, CLICKHOUSE).join();

            wireMock = WireMockUtils.startWireMock();

            var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                    CLICKHOUSE, DATABASE_NAME);

            mockBiEventResponse(DailyUsageReportJob.STATISTICS_BE, wireMock.server());
            mockBiEventResponse(BiEventListener.FIRST_TRACE_REPORT_BI_EVENT, wireMock.server());
            mockBiEventResponse(InstallationReportService.NOTIFICATION_EVENT_TYPE, wireMock.server());

            runMigrations(MYSQL, CLICKHOUSE);

            APP = newTestDropwizardAppExtension(
                    AppContextConfig.builder()
                            .jdbcUrl(MYSQL.getJdbcUrl())
                            .databaseAnalyticsFactory(databaseAnalyticsFactory)
                            .redisUrl(REDIS.getRedisURI())
                            .usageReportUrl("%s/v1/notify/event".formatted(wireMock.runtimeInfo().getHttpBaseUrl()))
                            .usageReportEnabled(true)
                            .metadataVersion(VERSION)
                            .build());
        }

        private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

        private String baseURI;
        private ClientSupport client;
        private ExperimentResourceClient experimentResourceClient;
        private TraceResourceClient traceResourceClient;
        private DatasetResourceClient datasetResourceClient;
        private ProjectResourceClient projectResourceClient;
        private TransactionTemplateAsync templateAsync;
        private TransactionTemplate transactionTemplate;

        @BeforeAll
        void setUpAll(ClientSupport client, TransactionTemplate transactionTemplate,
                TransactionTemplateAsync templateAsync) {

            this.baseURI = "http://localhost:%d".formatted(client.getPort());
            this.client = client;
            this.templateAsync = templateAsync;
            this.transactionTemplate = transactionTemplate;

            ClientSupportUtils.config(client);

            experimentResourceClient = new ExperimentResourceClient(this.client, baseURI, factory);
            traceResourceClient = new TraceResourceClient(this.client, baseURI);
            datasetResourceClient = new DatasetResourceClient(this.client, baseURI);
            projectResourceClient = new ProjectResourceClient(this.client, baseURI, factory);
        }

        @AfterAll
        void tearDownAll() {
            wireMock.server().stop();
            MYSQL.stop();
            CLICKHOUSE.stop();
            ZOOKEEPER_CONTAINER.stop();
            NETWORK.close();
        }

        @Test
        void test() throws SchedulerException {

            String workspaceName = "default";
            String apiKey = "";

            projectResourceClient.createProject(UUID.randomUUID().toString(), apiKey, workspaceName);

            setUpData(apiKey, workspaceName, ProjectService.DEFAULT_WORKSPACE_ID);

            var key = JobKey.jobKey(DailyUsageReportJob.class.getName());

            var trigger = TriggerBuilder.newTrigger().startNow().forJob(key).build();

            JobManagerUtils.getJobManager().getScheduler().scheduleJob(trigger);

            Awaitility
                    .await()
                    .atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        verifyResponse(wireMock.server(), "1", "1");
                    });

        }

        private void setUpData(String apiKey, String workspaceName, String workspaceId) {
            List<Dataset> datasets = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class);

            datasets.parallelStream().forEach(dataset -> {
                datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
            });

            List<Experiment> experiments = datasets
                    .stream()
                    .map(dataset -> experimentResourceClient.createPartialExperiment()
                            .datasetName(dataset.name())
                            .build())
                    .toList();

            experiments.parallelStream().forEach(experiment -> {
                experimentResourceClient.create(experiment, apiKey, workspaceName);
            });

            List<Trace> traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class);

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            createDemoData(apiKey, workspaceName);

            updateDatasets(workspaceId, transactionTemplate, false);
            updateExperiments(workspaceId, templateAsync, false);
            updateTraces(workspaceId, templateAsync, false);
        }

        private void createDemoData(String apiKey, String workspaceName) {

            DemoData.DATASETS.forEach(datasetName -> {
                Dataset dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                        .name(datasetName)
                        .build();

                datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
            });

            for (int i = 0; i < DemoData.EXPERIMENTS.size(); i++) {
                Experiment experiment = factory.manufacturePojo(Experiment.class).toBuilder()
                        .name(DemoData.EXPERIMENTS.get(i))
                        .datasetName(DemoData.DATASETS.get(i))
                        .promptVersion(null)
                        .promptVersions(null)
                        .build();

                experimentResourceClient.create(experiment, apiKey, workspaceName);
            }

            DemoData.PROJECTS.forEach(projectName -> {
                projectResourceClient.createProject(projectName, apiKey, workspaceName);

                List<Trace> traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                        .map(trace -> trace.toBuilder()
                                .projectName(projectName)
                                .build())
                        .toList();

                traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            });
        }
    }

}
