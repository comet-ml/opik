package com.comet.opik.infrastructure;

import com.comet.opik.api.Trace;
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
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@code bulkInsert.v2ClientEnabled} write path — feedback scores streamed to ClickHouse as
 * JSONEachRow through the v2 client instead of bound as named R2DBC parameters.
 *
 * <p>Why a separate class rather than a flag on the existing suites: the flag defaults to {@code false},
 * so every other test exercises the R2DBC path. Flipping it there would buy coverage of this path by
 * removing coverage of that one, and the R2DBC path is the one every install runs today. This class runs
 * the v2 path alongside them instead. Traces are created here only to hang scores off — that write still
 * goes through R2DBC.
 *
 * <p>It deliberately asserts only what a real server can settle, since the framing and the settings are
 * already unit-tested in {@code JsonEachRowBulkInsertTest}:
 *
 * <ul>
 *   <li><b>Type encodings</b> that JSONEachRow is stricter about than {@code FORMAT Values} — a
 *       {@code Decimal(18, 9)} value written as a quoted string, and a {@code FixedString(36)} queue id
 *       written as {@code ""} when absent.</li>
 *   <li><b>Escaping.</b> Row content carrying a newline and quotes must not split one JSONEachRow line
 *       into two, which would be a server-side parse error rather than a client-visible one.</li>
 *   <li><b>Omitted columns</b> taking their DDL defaults, which is what
 *       {@code input_format_defaults_for_omitted_fields} buys.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class BulkInsertV2ClientIntegrationTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysqlContainer = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);

    private final WireMockUtils.WireMockRuntime wireMock;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

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
                        .customConfigs(List.of(new CustomConfig("bulkInsert.v2ClientEnabled", "true")))
                        .build());
    }

    private TraceResourceClient traceResourceClient;
    private ConnectionFactory clickHouseConnectionFactory;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        clickHouseConnectionFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME).build();
    }

    private <T> T queryOne(String sql, Function<Row, T> mapper) {
        return Mono.usingWhen(
                clickHouseConnectionFactory.create(),
                connection -> Mono.from(connection.createStatement(sql).execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> mapper.apply(row)))),
                Connection::close)
                .block();
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
    }

    // Podam's generated id is kept (the factory produces a valid UUIDv7, which ingestion validation
    // requires), and only the sub-objects that would otherwise write scores of their own are nulled.
    private Trace.TraceBuilder newTraceBuilder() {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .usage(null)
                .errorInfo(null);
    }

    // FeedbackScoreService#getAuthor takes the author from the request context, so anything arriving
    // over HTTP has one and lands in authored_feedback_scores -- the 10-column form of the row. The
    // author-less feedback_scores form is only reachable from a context with no USER_NAME.
    private FeedbackScoreBatchItem newScore(UUID traceId, String projectName, String name) {
        return factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                .id(traceId)
                .projectName(projectName)
                .name(name)
                // Not "suite_assertion": that category routes to assertion_results instead
                // (ScoreDestination#fromCategoryName) and never reaches this write path.
                .categoryName("quality")
                .sourceQueueId(null)
                .build();
    }

    @Test
    @DisplayName("feedback scores round-trip their decimal value, reason, author and source queue id")
    void feedbackScoresRoundTrip() {
        // Decimal(18, 9) at full scale, written as a quoted plain string; and a reason carrying a
        // newline and quotes, which would end the JSONEachRow line early if it were not escaped.
        var trace = newTraceBuilder().build();
        traceResourceClient.batchCreateTraces(List.of(trace), API_KEY, WORKSPACE_NAME);

        var value = new BigDecimal("0.123456789");
        var reason = "line one\nline \"two\"\ttabbed 日本語";
        // A podam-generated trace id, which is a valid UUIDv7 -- ingestion rejects anything else.
        var sourceQueueId = factory.manufacturePojo(Trace.class).id();
        var score = newScore(trace.id(), trace.projectName(), "relevance").toBuilder()
                .value(value)
                .reason(reason)
                .sourceQueueId(sourceQueueId)
                .build();

        traceResourceClient.feedbackScores(List.of(score), API_KEY, WORKSPACE_NAME);

        var actual = traceResourceClient.getById(trace.id(), WORKSPACE_NAME, API_KEY);
        assertThat(actual.feedbackScores()).hasSize(1);
        var stored = actual.feedbackScores().getFirst();
        assertThat(stored.name()).isEqualTo("relevance");
        assertThat(stored.value()).isEqualByComparingTo(value);
        assertThat(stored.categoryName()).isEqualTo("quality");
        assertThat(stored.reason()).isEqualTo(reason);
        assertThat(stored.source()).isEqualTo(score.source());
        // The author and source_queue_id cells only surface through value_by_author. source_queue_id is
        // a FixedString(36) with no DEFAULT: the other test covers the absent case, written as "" and
        // read back as null, and this is the populated one.
        assertThat(stored.valueByAuthor()).hasSize(1);
        var entry = stored.valueByAuthor().values().iterator().next();
        assertThat(entry.author()).isEqualTo(USER);
        assertThat(entry.sourceQueueId()).isEqualTo(sourceQueueId.toString());
    }

    @Test
    @DisplayName("a feedback score batch writes each row once and server-stamps created_at and last_updated_at")
    void feedbackScoreBatchWritesEachRowOnceAndServerStampsTimestamps() {
        var trace = newTraceBuilder().build();
        traceResourceClient.batchCreateTraces(List.of(trace), API_KEY, WORKSPACE_NAME);

        // Distinct names, so each is its own row under the table's ORDER BY key rather than a dedup
        // candidate.
        var scores = List.of(
                newScore(trace.id(), trace.projectName(), "relevance"),
                newScore(trace.id(), trace.projectName(), "coherence"),
                newScore(trace.id(), trace.projectName(), "fluency"));

        traceResourceClient.feedbackScores(scores, API_KEY, WORKSPACE_NAME);

        // Raw rows, no FINAL: reads collapse duplicates, so cardinality is the only assertion that sees
        // a writer emitting every row twice.
        Long storedRows = queryOne(
                ("SELECT count() AS row_count FROM authored_feedback_scores WHERE workspace_id = '%s' "
                        + "AND entity_id = '%s'").formatted(WORKSPACE_ID, trace.id()),
                row -> row.get("row_count", Long.class));
        assertThat(storedRows).isEqualTo(scores.size());

        // Both timestamps are omitted from the JSON row so their DEFAULT now64(9) stamps them.
        // last_updated_at is the ReplacingMergeTree version, so a zero there would make every later
        // score for the same key lose to the original row.
        Long stampedRows = queryOne(
                ("SELECT count() AS row_count FROM authored_feedback_scores WHERE workspace_id = '%s' "
                        + "AND entity_id = '%s' AND created_at > toDateTime64('2000-01-01 00:00:00', 9) "
                        + "AND last_updated_at > toDateTime64('2000-01-01 00:00:00', 9)")
                        .formatted(WORKSPACE_ID, trace.id()),
                row -> row.get("row_count", Long.class));
        assertThat(stampedRows).isEqualTo(scores.size());

        var readBack = traceResourceClient.getById(trace.id(), WORKSPACE_NAME, API_KEY).feedbackScores();
        assertThat(readBack).hasSize(scores.size());
        // The absent source_queue_id half of the pair asserted in the round-trip test: written as "",
        // which the FixedString(36) zero-pads, and read back as null.
        assertThat(readBack)
                .allSatisfy(stored -> assertThat(stored.valueByAuthor().values())
                        .allSatisfy(entry -> assertThat(entry.sourceQueueId()).isNull()));
    }
}
