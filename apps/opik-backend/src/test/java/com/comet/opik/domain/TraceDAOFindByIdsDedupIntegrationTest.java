package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
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
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the span deduplication in {@code TraceDAO.SELECT_BY_IDS} (OPIK-7676).
 * <p>
 * Both span reads in that query used to be {@code FROM spans FINAL}. They were replaced with an
 * explicit {@code ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC,
 * last_updated_at DESC / LIMIT 1 BY id} dedup applied before the aggregation, which reads far less
 * data. Because {@code spans} is a ReplacingMergeTree and the aggregated columns are mutable, a bare
 * {@code FINAL} removal without that dedup would double-count every re-inserted span version.
 * <p>
 * Updating a span re-inserts a full row with the same id, so after the update below the table holds
 * two versions of the LLM span. Without dedup the trace would report 4 spans instead of 3, 2 LLM
 * spans instead of 1, and a summed token usage of 80 instead of the latest value of 70.
 * <p>
 * The duplicate version only exists until a background merge collapses it, which would silently rob
 * this test of its discriminating power. To prevent a false pass, the raw (non-deduplicated) row
 * count is asserted first and the test is skipped — not passed — if the second version is already
 * gone.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("TraceDAO findByIds span dedup Integration Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class TraceDAOFindByIdsDedupIntegrationTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private static final String PROVIDER = "openai";
    private static final String USAGE_KEY = "completion_tokens";
    private static final int USAGE_BEFORE_UPDATE = 10;
    private static final int USAGE_AFTER_UPDATE = 70;

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();

    @RegisterApp
    private final TestDropwizardAppExtension app;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
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

    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private TraceService traceService;
    private ConnectionFactory clickHouseConnectionFactory;

    @BeforeAll
    void setUpAll(ClientSupport client, Injector injector) {
        String baseUrl = TestUtils.getBaseUrl(client);

        // Swaps in GrizzlyConnectorProvider, without which the span update below cannot send PATCH.
        ClientSupportUtils.config(client);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);

        traceResourceClient = new TraceResourceClient(client, baseUrl);
        spanResourceClient = new SpanResourceClient(client, baseUrl);

        traceService = injector.getInstance(TraceService.class);

        clickHouseConnectionFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME).build();
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("findByIds: an updated span must not be counted twice in the trace aggregates")
    void findByIds__whenSpanWasUpdated__thenAggregatesUseLatestVersionOnly() {
        String projectName = "dedup-" + UUID.randomUUID();

        Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .usage(null)
                .feedbackScores(null)
                .build();
        UUID traceId = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

        UUID llmSpanId = createSpan(projectName, traceId, SpanType.llm,
                Map.of(USAGE_KEY, USAGE_BEFORE_UPDATE));
        createSpan(projectName, traceId, SpanType.tool, null);
        createSpan(projectName, traceId, SpanType.general, null);

        // Re-inserts a full row under the same id, leaving two versions of the LLM span behind.
        spanResourceClient.updateSpan(llmSpanId, SpanUpdate.builder()
                .projectName(projectName)
                .traceId(traceId)
                .usage(Map.of(USAGE_KEY, USAGE_AFTER_UPDATE))
                .build(), API_KEY, TEST_WORKSPACE);

        assumeTrue(rawVersionCount(llmSpanId) == 2,
                "background merge already collapsed the duplicate span version — nothing left to deduplicate");

        Trace actual = findByIds(traceId);

        assertThat(actual.spanCount()).isEqualTo(3);
        assertThat(actual.llmSpanCount()).isEqualTo(1);
        assertThat(actual.hasToolSpans()).isTrue();
        assertThat(actual.providers()).containsExactly(PROVIDER);
        // The latest version only — a summed 80 would mean both versions were aggregated.
        assertThat(actual.usage()).containsEntry(USAGE_KEY, (long) USAGE_AFTER_UPDATE);
    }

    private UUID createSpan(String projectName, UUID traceId, SpanType type, Map<String, Integer> usage) {
        Span span = factory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .traceId(traceId)
                .parentSpanId(null)
                .type(type)
                .provider(PROVIDER)
                .usage(usage)
                .totalEstimatedCost(null)
                .feedbackScores(null)
                .comments(null)
                .build();

        return spanResourceClient.createSpan(span, API_KEY, TEST_WORKSPACE);
    }

    /**
     * Raw row count for a span id, deliberately without FINAL or dedup, so it sees every stored version.
     */
    private static final String RAW_VERSION_COUNT = """
            SELECT count() AS version_count
            FROM spans
            WHERE workspace_id = :workspace_id
            AND id = :id
            """;

    private long rawVersionCount(UUID spanId) {
        return queryOne(RAW_VERSION_COUNT,
                statement -> statement
                        .bind("workspace_id", WORKSPACE_ID)
                        .bind("id", spanId),
                row -> row.get("version_count", Long.class));
    }

    private <T> T queryOne(String sql, Function<Statement, Statement> binder, Function<Row, T> mapper) {
        return Mono.usingWhen(
                clickHouseConnectionFactory.create(),
                connection -> Mono.from(binder.apply(connection.createStatement(sql)).execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> mapper.apply(row)))),
                Connection::close)
                .block();
    }

    private Trace findByIds(UUID traceId) {
        List<Trace> traces = traceService.getByIds(List.of(traceId))
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                .collectList()
                .block();

        assertThat(traces).hasSize(1);

        return traces.getFirst();
    }
}
