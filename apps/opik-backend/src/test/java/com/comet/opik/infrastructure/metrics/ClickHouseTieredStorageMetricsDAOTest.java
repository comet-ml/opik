package com.comet.opik.infrastructure.metrics;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO.MoveErrorStat;
import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO.TieredReadFailureStat;
import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO.TieredReadStat;
import io.r2dbc.spi.ConnectionFactory;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration coverage for the tiered-storage queries of {@link ClickHousePartitionMetricsDAO}, against a dedicated
 * ClickHouse with a cold tier (local blob storage behind a cache disk) and fast system-log flushing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseTieredStorageMetricsDAOTest {

    private static final String TIERED_STORAGE_CONFIG = "clickhouse-tiered-storage.xml";
    private static final String FAST_LOG_FLUSH_CONFIG = "clickhouse-fast-log-flush.xml";

    // Wide enough to hold every statement of the suite; the DAO also lags the window by its flush margin.
    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer(false,
            network);
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(false, network, zookeeperContainer)
            .withCopyFileToContainer(MountableFile.forClasspathResource(TIERED_STORAGE_CONFIG),
                    "/etc/clickhouse-server/config.d/%s".formatted(TIERED_STORAGE_CONFIG))
            .withCopyFileToContainer(MountableFile.forClasspathResource(FAST_LOG_FLUSH_CONFIG),
                    "/etc/clickhouse-server/config.d/%s".formatted(FAST_LOG_FLUSH_CONFIG));

    private ConnectionFactory connectionFactory;
    private ClickHousePartitionMetricsDAO dao;

    @BeforeAll
    void setUpAll() {
        Startables.deepStart(zookeeperContainer, clickHouseContainer).join();
        // Connects to `default`: the analytics database doesn't exist yet, and every statement here qualifies it.
        connectionFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouseContainer, "default")
                .build();
        dao = new ClickHousePartitionMetricsDAOImpl(connectionFactory, DATABASE_NAME);

        execute("CREATE DATABASE IF NOT EXISTS " + DATABASE_NAME);
        for (var table : new String[]{"tiered_reads", "tiered_failures", "plain_reads"}) {
            var policy = table.startsWith("tiered") ? " SETTINGS storage_policy = 'tiered'" : "";
            execute("CREATE TABLE %s.%s (id UInt64, p UInt8) ENGINE = MergeTree PARTITION BY p ORDER BY id%s"
                    .formatted(DATABASE_NAME, table, policy));
            execute("INSERT INTO %s.%s SELECT number, number %% 2 FROM numbers(10000)".formatted(DATABASE_NAME, table));
        }
        execute("ALTER TABLE %s.tiered_reads MOVE PARTITION 0 TO VOLUME 'cold'".formatted(DATABASE_NAME));
    }

    @AfterAll
    void tearDownAll() {
        try {
            clickHouseContainer.stop();
            zookeeperContainer.stop();
        } finally {
            network.close();
        }
    }

    @Test
    void countsReadsOfTieredTablesAndWhichOfThemReadTheColdTier() {
        for (int i = 0; i < 3; i++) {
            execute("SELECT sum(id) FROM %s.tiered_reads WHERE p = 0".formatted(DATABASE_NAME));
            execute("SELECT sum(id) FROM %s.tiered_reads WHERE p = 1".formatted(DATABASE_NAME));
            execute("SELECT sum(id) FROM %s.plain_reads".formatted(DATABASE_NAME));
        }

        // Past the DAO's flush lag, the six reads of tiered_reads are in the window: half of them on the cold tier.
        // plain_reads has no cold-tier policy, so it never reports.
        Awaitility.await().atMost(60, TimeUnit.SECONDS).pollInterval(2, TimeUnit.SECONDS).untilAsserted(() -> {
            var stats = dao.getTieredReadStats(WINDOW).block();

            assertThat(stats).extracting(TieredReadStat::table).containsExactly("tiered_reads");
            assertThat(stats.getFirst().queries()).isEqualTo(6);
            assertThat(stats.getFirst().coldQueries()).isEqualTo(3);
        });
    }

    @Test
    void countsFailedReadsWithAColdReadErrorCodeOnly() {
        // S3_ERROR (499) is a cold-read failure; throwIf's own code (395) stands in for the routine failures to ignore.
        for (int i = 0; i < 2; i++) {
            executeIgnoringFailure(
                    ("SELECT throwIf(id >= 0, 'simulated cold read failure', toInt32(499)) FROM %s.tiered_failures"
                            + " SETTINGS allow_custom_error_code_in_throwif = 1").formatted(DATABASE_NAME));
            executeIgnoringFailure("SELECT throwIf(id >= 0) FROM %s.tiered_failures".formatted(DATABASE_NAME));
        }
        // A failed write with the same code isn't a read.
        executeIgnoringFailure(
                ("INSERT INTO %s.tiered_failures SELECT throwIf(number >= 0, 'simulated', toInt32(499)), 0"
                        + " FROM numbers(1) SETTINGS allow_custom_error_code_in_throwif = 1").formatted(DATABASE_NAME));

        Awaitility.await().atMost(60, TimeUnit.SECONDS).pollInterval(2, TimeUnit.SECONDS).untilAsserted(
                () -> assertThat(dao.getTieredReadFailures(WINDOW).block()).containsExactly(
                        TieredReadFailureStat.builder().table("tiered_failures").code("S3_ERROR").failedQueries(2)
                                .build()));
    }

    @Test
    void reportsNoMoveErrorsForSuccessfulMoves() {
        // The move in setUpAll is logged as a successful MovePart; the query must find it and not count it.
        Awaitility.await().atMost(60, TimeUnit.SECONDS).pollInterval(2, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(queryLong(("SELECT toInt64(count()) FROM system.part_log WHERE event_type = 'MovePart'"
                    + " AND database = '%s' AND table = 'tiered_reads' AND error = 0"
                    + " AND event_time < now() - INTERVAL 15 SECOND").formatted(DATABASE_NAME))).isPositive();
            assertThat(dao.getMoveErrors(WINDOW).block()).isEmpty();
        });
    }

    @Test
    void countsFailedMoves() throws Exception {
        execute("CREATE TABLE %s.failed_moves (id UInt64, p UInt8) ENGINE = MergeTree PARTITION BY p ORDER BY id"
                .formatted(DATABASE_NAME) + " SETTINGS storage_policy = 'broken'");
        execute("INSERT INTO %s.failed_moves SELECT number, 0 FROM numbers(100)".formatted(DATABASE_NAME));
        assertThat(clickHouseContainer.execInContainer("chmod", "-R", "a-w", "/var/lib/clickhouse/disks/broken_blob")
                .getExitCode()).isZero();
        executeIgnoringFailure(
                "ALTER TABLE %s.failed_moves MOVE PARTITION 0 TO VOLUME 'cold'".formatted(DATABASE_NAME));

        Awaitility.await().atMost(60, TimeUnit.SECONDS).pollInterval(2, TimeUnit.SECONDS).untilAsserted(
                () -> assertThat(dao.getMoveErrors(WINDOW).block()).containsExactly(
                        MoveErrorStat.builder().table("failed_moves").moveErrors(1).build()));
    }

    private void execute(String sql) {
        Flux.usingWhen(connectionFactory.create(),
                connection -> Flux.from(connection.createStatement(sql).execute())
                        .flatMap(result -> result.getRowsUpdated()),
                connection -> connection.close())
                .blockLast();
    }

    private void executeIgnoringFailure(String sql) {
        Mono.fromRunnable(() -> execute(sql)).onErrorResume(exception -> Mono.empty()).block();
    }

    private long queryLong(String sql) {
        return Flux.usingWhen(connectionFactory.create(),
                connection -> Flux.from(connection.createStatement(sql).execute())
                        .flatMap(result -> result.map((row, metadata) -> row.get(0, Long.class))),
                connection -> connection.close())
                .blockFirst();
    }
}
