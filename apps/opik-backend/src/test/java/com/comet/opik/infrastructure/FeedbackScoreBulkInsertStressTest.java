package com.comet.opik.infrastructure;

import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.FeedbackScoreDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;

/**
 * Local A/B harness for the two feedback-score bulk write paths. Not a test — it asserts nothing and
 * is not meant to run in CI; it exists to put numbers on {@code bulkInsert.v2ClientEnabled}.
 *
 * <p>Select the arm with a system property and run it twice:
 *
 * <pre>
 * mvn -o test -Dtest=FeedbackScoreBulkInsertStressTest -Dstress.v2=false
 * mvn -o test -Dtest=FeedbackScoreBulkInsertStressTest -Dstress.v2=true
 * </pre>
 *
 * <p>It drives {@code FeedbackScoreDAO#scoreBatchOf} directly rather than the HTTP endpoint, because
 * the endpoint caps a batch at 1000 items while {@code ExperimentItemBulkIngestionService} can hand
 * the DAO an order of magnitude more — and because the thing under measurement is the write path, not
 * Jersey.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class FeedbackScoreBulkInsertStressTest {

    private static final boolean V2 = Boolean.parseBoolean(System.getProperty("stress.v2", "false"));

    /**
     * Results go to a file, not stdout. The app logs on the same stream from its own threads, and a
     * printf is not atomic against that: the first run of this harness lost the 50k line to a logback
     * record written through the middle of it, leaving the number recoverable only from Surefire's
     * wall time.
     */
    private static final Path RESULTS = Path.of(System.getProperty("stress.out", "target/stress-results.txt"));

    /** Comma-separated sizes to run, e.g. {@code -Dstress.sizes=50000}. Empty runs all of them. */
    private static final String SIZES = System.getProperty("stress.sizes", "");

    // Sizes climb geometrically so the SHAPE of the curve is visible, not just two endpoints: the
    // R2DBC path binds one named parameter per column per row and resolves each by linear scan, so
    // its cost should grow faster than the row count while the JSONEachRow path should stay flat.
    private static final int[] BATCH_SIZES = {1_000, 2_000, 5_000, 10_000, 20_000, 50_000};
    // Free text is the only unbounded column on this row; keep it realistic rather than empty.
    private static final int REASON_CHARS = 120;

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "stress-user";
    private static final String AUTHOR = "stress-author";

    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysqlContainer = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer).join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .customConfigs(List.of(
                                new CustomConfig("bulkInsert.v2ClientEnabled", String.valueOf(V2))))
                        .build());
    }

    private FeedbackScoreDAO feedbackScoreDAO;

    @BeforeAll
    void beforeAll(FeedbackScoreDAO feedbackScoreDAO) {
        this.feedbackScoreDAO = feedbackScoreDAO;
    }

    private List<FeedbackScoreBatchItem> buildScores(int size, UUID projectId) {
        var reason = RandomStringUtils.secure().nextAlphanumeric(REASON_CHARS);
        var scores = new ArrayList<FeedbackScoreBatchItem>(size);
        for (int i = 0; i < size; i++) {
            scores.add(FeedbackScoreBatchItem.builder()
                    .id(UUID.randomUUID())
                    .projectId(projectId)
                    .name("metric-" + (i % 20))
                    .categoryName("quality")
                    .value(BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble())
                            .setScale(9, java.math.RoundingMode.HALF_UP))
                    .reason(reason)
                    .source(ScoreSource.SDK)
                    .build());
        }
        return scores;
    }

    private long timeOneBatch(int size, UUID projectId) {
        var scores = buildScores(size, projectId);
        long start = System.nanoTime();
        feedbackScoreDAO.scoreBatchOf(EntityType.TRACE, scores, AUTHOR)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                .block();
        return System.nanoTime() - start;
    }

    // One invocation per size so each gets its own timeout budget: a single batch on the R2DBC arm at
    // the top sizes takes minutes, and a single method covering every size blows the 4m default before
    // the large sizes are reached -- which reads as "failed" when the truth is "did not get a turn".
    // Explicit @Timeout overrides that default rather than relying on a -D on the command line.
    @ParameterizedTest(name = "batch={0}")
    @ValueSource(ints = {1_000, 2_000, 5_000, 10_000, 20_000, 50_000})
    @Timeout(value = 25, unit = java.util.concurrent.TimeUnit.MINUTES)
    void stress(int size) {
        org.junit.jupiter.api.Assumptions.assumeTrue(SIZES.isBlank()
                || Arrays.stream(SIZES.split(",")).map(String::trim).anyMatch(s -> s.equals(String.valueOf(size))),
                "skipped by -Dstress.sizes");

        var projectId = UUID.randomUUID();
        // Large sizes get fewer repetitions: on the R2DBC arm one 50k batch is minutes, and the point
        // of the top end is the order of magnitude, not a tight confidence interval.
        int warmups = size <= 10_000 ? 1 : 0;
        int runs = size <= 10_000 ? 3 : 2;

        try {
            for (int i = 0; i < warmups; i++) {
                timeOneBatch(size, projectId);
            }
            var samples = new ArrayList<Long>(runs);
            for (int i = 0; i < runs; i++) {
                samples.add(timeOneBatch(size, projectId));
            }
            samples.sort(Long::compare);
            double best = samples.getFirst() / 1_000_000.0;
            double median = samples.get(samples.size() / 2) / 1_000_000.0;
            System.out.printf(
                    "STRESS|v2=%s|batch=%d|runs=%d|warmups=%d|best_ms=%.1f|median_ms=%.1f|rows_per_sec=%.0f%n",
                    V2, size, runs, warmups, best, median, size / (median / 1000.0));
        } catch (Throwable t) {
            var cause = t.getCause() != null ? t.getCause() : t;
            System.out.printf("STRESS|v2=%s|batch=%d|FAILED|%s: %s%n", V2, size,
                    cause.getClass().getSimpleName(),
                    String.valueOf(cause.getMessage()).lines().findFirst().orElse(""));
        }
    }
}
