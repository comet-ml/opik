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
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JobManagerUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
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

    private static final String SUCCESS_RESPONSE = "{\"message\":\"Event added successfully\",\"success\":\"true\"}";

    private static final String USER = UUID.randomUUID().toString();
    private static final String VERSION = "%s.%s.%s".formatted(PodamUtils.getIntegerInRange(1, 99),
            PodamUtils.getIntegerInRange(1, 99), PodamUtils.getIntegerInRange(1, 99));

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer(false);
    private static final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(false);

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final WireMockUtils.WireMockRuntime wireMock;

    static {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, DATABASE_NAME);

        wireMock.server().stubFor(
                post(urlPathEqualTo("/v1/notify/event"))
                        .withRequestBody(matchingJsonPath("$.anonymous_id", matching(
                                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")))
                        .withRequestBody(matchingJsonPath("$.event_type",
                                matching(DailyUsageReportJob.STATISTICS_BE)))
                        .withRequestBody(matchingJsonPath("$.event_properties.opik_app_version", matching(VERSION)))
                        .willReturn(WireMock.okJson(SUCCESS_RESPONSE)));

        wireMock.server().stubFor(
                post(urlPathEqualTo("/v1/notify/event"))
                        .withRequestBody(matchingJsonPath("$.anonymous_id", matching(
                                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")))
                        .withRequestBody(matchingJsonPath("$.event_type",
                                matching(InstallationReportService.NOTIFICATION_EVENT_TYPE)))
                        .withRequestBody(matchingJsonPath("$.event_properties.opik_app_version", matching(VERSION)))
                        .willReturn(WireMock.okJson(SUCCESS_RESPONSE)));

        try {
            MigrationUtils.runDbMigration(MYSQL.createConnection(""),
                    MySQLContainerUtils.migrationParameters());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        try (var connection = CLICKHOUSE.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        app = newTestDropwizardAppExtension(
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
    private TransactionTemplateAsync templateAsync;
    private ProjectResourceClient projectResourceClient;
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
    }

    private static void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
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
                    wireMock.server().verify(
                            postRequestedFor(urlPathEqualTo("/v1/notify/event"))
                                    .withRequestBody(matchingJsonPath("$.anonymous_id", matching(
                                            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))
                                            .and(matchingJsonPath("$.event_type",
                                                    equalTo(DailyUsageReportJob.STATISTICS_BE)))
                                            .and(matchingJsonPath("$.event_properties.total_users", equalTo("17")))
                                            .and(matchingJsonPath("$.event_properties.opik_app_version",
                                                    equalTo(VERSION)))
                                            .and(matchingJsonPath("$.event_properties.daily_users", equalTo("15")))
                                            .and(matchingJsonPath("$.event_properties.daily_traces", equalTo("5")))
                                            .and(matchingJsonPath("$.event_properties.daily_experiments", equalTo("5")))
                                            .and(matchingJsonPath("$.event_properties.daily_datasets", equalTo("5")))));
                });

    }

    private void setUpData(String apiKey, String workspaceName, String workspaceId) {
        List<Dataset> datasets = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class);

        datasets.parallelStream().forEach(dataset -> {
            datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
        });

        List<Experiment> experiments = datasets
                .stream()
                .map(dataset -> factory.manufacturePojo(Experiment.class).toBuilder()
                        .datasetId(dataset.id())
                        .datasetName(dataset.name())
                        .promptVersion(null)
                        .build())
                .toList();

        experiments.parallelStream().forEach(experiment -> {
            experimentResourceClient.create(experiment, apiKey, workspaceName);
        });

        List<Trace> traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class);

        traces.parallelStream().forEach(trace -> {
            traceResourceClient.createTrace(trace, apiKey, workspaceName);
        });

        transactionTemplate
                .inTransaction(TransactionTemplateAsync.WRITE, handle -> handle.createUpdate("""
                            UPDATE datasets
                            SET created_at = now() - INTERVAL 1 DAY,
                                last_updated_at = now() - INTERVAL 1 DAY,
                                last_updated_by = id,
                                created_by = id
                            WHERE workspace_id = :workspace_id
                        """)
                        .bind("workspace_id", workspaceId)
                        .execute());

        templateAsync.nonTransaction(connection -> Mono.from(connection.createStatement("""
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
                    toString(generateUUIDv4()),
                    toString(generateUUIDv4()),
                    created_at - INTERVAL 1 DAY,
                    last_updated_at - INTERVAL 1 DAY
                FROM experiments
                WHERE workspace_id = :workspace_id
                ;
                """)
                .bind("workspace_id", workspaceId)
                .execute())).block();

        templateAsync.nonTransaction(connection -> Mono.from(connection.createStatement("""
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
                    toString(generateUUIDv4()),
                    toString(generateUUIDv4())
                FROM traces
                WHERE workspace_id = :workspace_id
                ;
                """)
                .bind("workspace_id", workspaceId)
                .execute())).block();
    }
}