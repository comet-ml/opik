package com.comet.opik.infrastructure;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanSearchStreamRequest;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trace-scoped span reads must stay on the {@code (workspace_id, project_id, trace_id, ...)} primary-key prefix in
 * every read of {@code spans}, including {@code SELECT_BY_PROJECT_ID}'s {@code page_wide} re-read (issue 8936).
 * Without {@code trace_id} there, that re-read can only use {@code (workspace_id, project_id, id)} and scans the whole
 * project, while returning the same rows — so API results alone cannot catch the regression.
 *
 * <p>The executed statement is taken from {@code query_log} and run through {@code EXPLAIN indexes = 1}; the guard is
 * on the primary-key columns each {@code spans} read uses, which holds regardless of how small the test data is.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpansTraceScopedReadPrimaryKeyTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    private static final String FIND_SPANS = "find_spans_by_project_id";
    private static final String FIND_SPAN_STREAM = "find_span_stream";

    /** The latest executed statement for an op that mentions the given trace id. */
    private static final String LAST_QUERY = """
            SELECT query
            FROM system.query_log
            WHERE log_comment LIKE concat(:op, ':%')
            AND type = 'QueryFinish'
            AND query LIKE concat('%', :trace_id, '%')
            ORDER BY event_time_microseconds DESC
            LIMIT 1
            """;

    private static final String FAST_LOG_FLUSH_CONFIG = "clickhouse-fast-log-flush.xml";

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer(false,
            network);
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(false, network, zookeeperContainer)
            .withCopyFileToContainer(MountableFile.forClasspathResource(FAST_LOG_FLUSH_CONFIG),
                    "/etc/clickhouse-server/config.d/%s".formatted(FAST_LOG_FLUSH_CONFIG));
    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysqlContainer = MySQLContainerUtils.newMySQLContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer).join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, ClickHouseContainerUtils.DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .build());
    }

    private SpanResourceClient spanResourceClient;
    private TransactionTemplateAsync template;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, TransactionTemplateAsync template) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        this.spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        this.template = template;
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    @Test
    void findSpansByTraceIdReadsSpansThroughTheTraceIdPrefix() {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(16);
        var traceId = createSpans(projectName, 3);
        createSpans(projectName, 3); // a second trace in the same project

        var page = spanResourceClient.getByTraceIdAndProject(traceId, projectName, WORKSPACE_NAME, API_KEY);

        assertThat(page.content()).hasSize(3).allMatch(span -> traceId.equals(span.traceId()));
        assertEverySpansReadUsesTraceId(FIND_SPANS, traceId);
    }

    @Test
    void searchSpansByTraceIdReadsSpansThroughTheTraceIdPrefix() {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(16);
        var traceId = createSpans(projectName, 3);
        createSpans(projectName, 3); // a second trace in the same project

        var spans = spanResourceClient.getStreamAndAssertContent(API_KEY, WORKSPACE_NAME,
                SpanSearchStreamRequest.builder().projectName(projectName).traceId(traceId).build());

        assertThat(spans).hasSize(3).allMatch(span -> traceId.equals(span.traceId()));
        assertEverySpansReadUsesTraceId(FIND_SPAN_STREAM, traceId);
    }

    private void assertEverySpansReadUsesTraceId(String op, UUID traceId) {
        var explain = explain(lastQuery(op, traceId));
        var spansReads = JsonUtils.getJsonNodeFromString(explain).findParents("Node Type").stream()
                .filter(node -> "ReadFromMergeTree".equals(node.path("Node Type").asText()))
                .filter(node -> node.path("Description").asText().endsWith(".spans"))
                .toList();

        assertThat(spansReads).as("spans reads in EXPLAIN output:%n%s", explain).isNotEmpty();
        assertThat(spansReads).allSatisfy(read -> assertThat(primaryKeyColumns(read))
                .as("primary-key columns of %s in EXPLAIN output:%n%s", read.path("Node Id").asText(), explain)
                .contains("trace_id"));
    }

    private List<String> primaryKeyColumns(JsonNode read) {
        return read.path("Indexes").valueStream()
                .filter(index -> "PrimaryKey".equals(index.path("Type").asText()))
                .flatMap(index -> index.path("Keys").valueStream())
                .map(JsonNode::asText)
                .toList();
    }

    /** Creates {@code count} spans in a new trace and returns its id. */
    private UUID createSpans(String projectName, int count) {
        var traceId = UUID.randomUUID();
        var spans = IntStream.range(0, count)
                .mapToObj(i -> factory.manufacturePojo(Span.class).toBuilder()
                        .projectName(projectName)
                        .traceId(traceId)
                        .parentSpanId(null)
                        .feedbackScores(null)
                        .build())
                .toList();
        spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);
        return traceId;
    }

    /** Polled: a query's {@code query_log} row is written asynchronously, flushed every 200 ms here. */
    private String lastQuery(String op, UUID traceId) {
        return Awaitility.await()
                .alias("query_log holds a %s statement mentioning trace %s".formatted(op, traceId))
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> template.nonTransaction(connection -> Mono.from(connection.createStatement(LAST_QUERY)
                        .bind("op", op)
                        .bind("trace_id", traceId.toString())
                        .execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get(0, String.class)))))
                        .block(), Objects::nonNull);
    }

    private String explain(String query) {
        var statement = query.strip().replaceAll(";$", "");
        return String.join("\n", template.stream(connection -> Flux.from(connection
                .createStatement("EXPLAIN indexes = 1, json = 1 %s".formatted(statement))
                .execute())
                .flatMap(result -> result.map((row, ignored) -> row.get("explain", String.class))))
                .collectList()
                .block());
    }
}
