package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.ClickHousePartitionMetricsDAO.LwdStat;
import com.comet.opik.domain.ClickHousePartitionMetricsDAO.PartitionStat;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.ConnectionFactory;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration coverage for {@link ClickHousePartitionMetricsDAO} (OPIK-6904). Seeds traces through
 * the API, then asserts the system.parts aggregation and the LWD-masked row count against
 * ClickHouse ground truth. The job itself is thin lock+snapshot glue over this DAO, so exercising
 * the DAO covers the metric-sourcing logic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ClickHousePartitionMetricsDAOTest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private ClickHousePartitionMetricsDAO partitionMetricsDAO;
    private ConnectionFactory connectionFactory;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, Injector injector) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        partitionMetricsDAO = injector.getInstance(ClickHousePartitionMetricsDAO.class);
        connectionFactory = injector.getInstance(ConnectionFactory.class);
    }

    @Test
    void reportsPartitionStatsAndLwdRowsForTraces() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectName = randomName("project");
        projectResourceClient.createProject(
                factory.manufacturePojo(Project.class).toBuilder().name(projectName).build(), apiKey, workspaceName);

        int traceCount = 15;
        var traceIds = new ArrayList<UUID>();
        for (int i = 0; i < traceCount; i++) {
            traceIds.add(traceResourceClient.createTrace(
                    factory.manufacturePojo(Trace.class).toBuilder()
                            .id(null)
                            .projectName(projectName)
                            .projectId(null)
                            .startTime(Instant.now())
                            .feedbackScores(null)
                            .usage(null)
                            .build(),
                    apiKey, workspaceName));
        }

        // system.parts aggregation: once the inserts land, traces must show up with a well-formed
        // single-partition row (the table is unpartitioned pre-OPIK-6875, so partition_id = 'all').
        Awaitility.await().atMost(30, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(() -> {
            PartitionStat traces = tracesStat();
            assertThat(traces).isNotNull();
            assertThat(traces.rows()).isGreaterThanOrEqualTo(traceCount);
            assertThat(traces.bytes()).isPositive();
            assertThat(traces.maxPartBytes()).isPositive();
            assertThat(traces.parts()).isPositive();
            assertThat(traces.lastActivityEpochSeconds())
                    .isGreaterThan(Instant.now().minusSeconds(600).getEpochSecond());
        });

        // Lightweight-delete a subset and assert the DAO's masked-row count matches ClickHouse
        // ground truth (cross-checking the DAO SQL rather than an exact physical-row count keeps
        // the assertion robust to ReplacingMergeTree duplicates).
        int toDelete = 5;
        var idList = traceIds.stream().limit(toDelete)
                .map(id -> "'%s'".formatted(id))
                .toList();
        execute("DELETE FROM traces WHERE id IN (%s)".formatted(String.join(",", idList)));

        Awaitility.await().atMost(30, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(() -> {
            long daoLwd = partitionMetricsDAO.getLwdRowCounts(List.of("traces")).block().stream()
                    .filter(stat -> stat.table().equals("traces"))
                    .mapToLong(LwdStat::lwdRows)
                    .sum();
            long groundTruth = queryLong(
                    "SELECT toInt64(count()) FROM traces WHERE _row_exists = 0 SETTINGS apply_deleted_mask = 0");
            assertThat(groundTruth).isGreaterThanOrEqualTo(toDelete);
            assertThat(daoLwd).isEqualTo(groundTruth);
        });
    }

    private PartitionStat tracesStat() {
        return partitionMetricsDAO.getPartitionStats().block().stream()
                .filter(stat -> stat.table().equals("traces"))
                .findFirst()
                .orElse(null);
    }

    private void execute(String sql) {
        Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(sql).execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated()))
                .then()
                .block();
    }

    private long queryLong(String sql) {
        return Optional.ofNullable(Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(sql).execute())
                .flatMap(result -> result.map((row, metadata) -> row.get(0, Long.class)))
                .blockFirst()).orElse(0L);
    }

    private String randomName(String prefix) {
        return "%s-%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(32));
    }
}
