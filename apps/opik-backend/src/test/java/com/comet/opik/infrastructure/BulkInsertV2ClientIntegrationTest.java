package com.comet.opik.infrastructure;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.Span;
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
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@code bulkInsert.v2ClientEnabled} write path — traces and spans streamed to ClickHouse
 * as JSONEachRow through the v2 client instead of bound as named R2DBC parameters.
 *
 * <p>Why a separate class rather than a flag on the existing suites: the flag defaults to {@code false},
 * so every other test — including {@code TraceSentinelIntegrationTest} and
 * {@code SpanSentinelIntegrationTest} — exercises the R2DBC path. Flipping it there would buy coverage
 * of this path by removing coverage of that one, and the R2DBC path is what the traces cutover depends
 * on. This class runs the v2 path alongside them instead.
 *
 * <p>It deliberately asserts only what a real server can settle, since the framing and the settings are
 * already unit-tested in {@code JsonEachRowBulkInsertTest}:
 *
 * <ul>
 *   <li><b>Type encodings</b> that JSONEachRow is stricter about than {@code FORMAT Values} — a
 *       {@code Decimal128(12)} cost written as a quoted string, {@code Map(String, Int)} usage,
 *       {@code Array(String)} tags.</li>
 *   <li><b>The sentinels.</b> Both non-nullable flags are on, so an absent {@code end_time}/{@code ttft}
 *       is written as the epoch/NaN sentinel. {@code NaN} is not valid JSON, so Jackson quotes it and the
 *       insert relies on {@code input_format_json_read_numbers_as_strings}; nothing but a live server can
 *       confirm that round-trips.</li>
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
                        .customConfigs(List.of(
                                new CustomConfig("bulkInsert.v2ClientEnabled", "true"),
                                new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true"),
                                new CustomConfig("databaseAnalyticsDataModel.spanColumnsNonNullable", "true")))
                        .build());
    }

    private TraceResourceClient traceResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private SpanResourceClient spanResourceClient;
    private ConnectionFactory clickHouseConnectionFactory;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        clickHouseConnectionFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME).build();
    }

    private <T> T queryOne(String sql, java.util.function.Function<Row, T> mapper) {
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

    // Mirrors the sibling sentinel suites: podam's generated id is kept (the factory produces a valid
    // UUIDv7, which ingestion validation requires), and only the sub-objects those suites null are nulled.
    private Trace.TraceBuilder newTraceBuilder() {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .usage(null)
                .errorInfo(null);
    }

    private Span.SpanBuilder newSpanBuilder() {
        return factory.manufacturePojo(Span.class).toBuilder()
                .feedbackScores(null)
                .usage(null)
                .errorInfo(null);
    }

    @Test
    @DisplayName("a batch of traces round-trips through JSONEachRow")
    void tracesRoundTrip() {
        var trace = newTraceBuilder().build();

        traceResourceClient.batchCreateTraces(List.of(trace), API_KEY, WORKSPACE_NAME);

        var actual = traceResourceClient.getById(trace.id(), WORKSPACE_NAME, API_KEY);
        assertThat(actual.id()).isEqualTo(trace.id());
        assertThat(actual.name()).isEqualTo(trace.name());
        assertThat(actual.input()).isEqualTo(trace.input());
        assertThat(actual.output()).isEqualTo(trace.output());
        assertThat(actual.tags()).isEqualTo(trace.tags());
    }

    @Test
    @DisplayName("absent end_time and ttft round-trip as null through the sentinels")
    void absentSentinelsRoundTrip() {
        // Both non-nullable flags are on, so these are written as the epoch and NaN sentinels. NaN is
        // not valid JSON: Jackson quotes it, and the insert sets
        // input_format_json_read_numbers_as_strings so ClickHouse parses it back.
        var trace = newTraceBuilder().endTime(null).ttft(null).build();

        traceResourceClient.batchCreateTraces(List.of(trace), API_KEY, WORKSPACE_NAME);

        var actual = traceResourceClient.getById(trace.id(), WORKSPACE_NAME, API_KEY);
        assertThat(actual.endTime()).isNull();
        assertThat(actual.ttft()).isNull();
    }

    @Test
    @DisplayName("a batch of spans round-trips its decimal cost, usage map and tags")
    void spansRoundTripTypedColumns() {
        // The three encodings JSONEachRow is strictest about: Decimal128(12) written as a quoted plain
        // string, Map(String, Int), and Array(String).
        var cost = new BigDecimal("0.000123456789");
        var span = newSpanBuilder()
                .totalEstimatedCost(cost)
                .usage(Map.of("prompt_tokens", 12, "completion_tokens", 8))
                .tags(java.util.Set.of("alpha", "beta"))
                .build();

        spanResourceClient.batchCreateSpans(List.of(span), API_KEY, WORKSPACE_NAME);

        var actual = spanResourceClient.getById(span.id(), WORKSPACE_NAME, API_KEY);
        assertThat(actual.totalEstimatedCost()).isEqualByComparingTo(cost);
        assertThat(actual.usage()).containsExactlyInAnyOrderEntriesOf(
                Map.of("prompt_tokens", 12, "completion_tokens", 8));
        assertThat(actual.tags()).containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    @DisplayName("absent span end_time and ttft round-trip as null through the sentinels")
    void absentSpanSentinelsRoundTrip() {
        var span = newSpanBuilder().endTime(null).ttft(null).build();

        spanResourceClient.batchCreateSpans(List.of(span), API_KEY, WORKSPACE_NAME);

        var actual = spanResourceClient.getById(span.id(), WORKSPACE_NAME, API_KEY);
        assertThat(actual.endTime()).isNull();
        assertThat(actual.ttft()).isNull();
    }

    @Test
    @DisplayName("row content with newlines and quotes does not split a JSONEachRow line")
    void contentWithNewlinesAndQuotesSurvives() {
        // A literal newline in the payload would end the row early and the next row would parse as
        // garbage — server-side, far from the cause. This is the escaping guarantee, verified end to end.
        var awkward = JsonUtils.getJsonNodeFromString(
                "{\"text\": \"line one\\nline \\\"two\\\"\\ttabbed\", \"unicode\": \"日本語 — ok\"}");
        var first = newTraceBuilder().input(awkward).build();
        var second = newTraceBuilder().build();

        traceResourceClient.batchCreateTraces(List.of(first, second), API_KEY, WORKSPACE_NAME);

        // Both rows must land: if the first split the line, the second would be lost or corrupt.
        assertThat(traceResourceClient.getById(first.id(), WORKSPACE_NAME, API_KEY).input()).isEqualTo(awkward);
        assertThat(traceResourceClient.getById(second.id(), WORKSPACE_NAME, API_KEY).id()).isEqualTo(second.id());
    }

    @Test
    @DisplayName("a multi-row batch writes each row exactly once")
    void multiRowBatchWritesEachRowExactlyOnce() {
        var traces = List.of(newTraceBuilder().build(), newTraceBuilder().build(), newTraceBuilder().build());

        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

        // Read the RAW rows, no FINAL and no LIMIT 1 BY id: getById collapses duplicates, so a writer
        // that emitted every row twice would satisfy a per-id existence check. Cardinality is the only
        // assertion that can see it.
        var ids = traces.stream().map(trace -> "'" + trace.id() + "'")
                .collect(java.util.stream.Collectors.joining(","));
        Long storedRows = queryOne(
                "SELECT count() AS row_count FROM traces WHERE workspace_id = '%s' AND id IN (%s)"
                        .formatted(WORKSPACE_ID, ids),
                row -> row.get("row_count", Long.class));

        assertThat(storedRows).isEqualTo(traces.size());
        traces.forEach(trace -> assertThat(
                traceResourceClient.getById(trace.id(), WORKSPACE_NAME, API_KEY).id()).isEqualTo(trace.id()));
    }

    @Test
    @DisplayName("start_time keeps sub-second precision through the client-side formatter")
    void startTimeKeepsPrecision() {
        // The rows carry client-formatted DateTime64 literals parsed with date_time_input_format=best_effort.
        var startTime = Instant.parse("2026-09-07T10:11:12.123456Z");
        var trace = newTraceBuilder().startTime(startTime).endTime(startTime.plusSeconds(2)).build();

        traceResourceClient.batchCreateTraces(List.of(trace), API_KEY, WORKSPACE_NAME);

        assertThat(traceResourceClient.getById(trace.id(), WORKSPACE_NAME, API_KEY).startTime())
                .isEqualTo(startTime);
    }

    // ------------------------------------------------------------------ dataset items ---
    // Dataset versioning defaults to ON (TOGGLE_DATASET_VERSIONING_ENABLED), so PUT /datasets/items
    // lands in DatasetItemVersionDAO#insertItems -- dataset_item_versions, 23 columns, the widest bulk
    // write here. DatasetItemDAO#save covers the legacy dataset_items table on the same flag.

    private DatasetItem newDatasetItem(Map<String, com.fasterxml.jackson.databind.JsonNode> data) {
        return DatasetItem.builder()
                .id(factory.manufacturePojo(DatasetItem.class).id())
                .source(DatasetItemSource.MANUAL)
                .data(data)
                .build();
    }

    private UUID newDataset() {
        return datasetResourceClient.createDataset(
                DatasetResourceClient.buildDataset(factory), API_KEY, WORKSPACE_NAME);
    }

    @Test
    @DisplayName("dataset items round-trip their data map, tags and source through JSONEachRow")
    void datasetItemsRoundTrip() {
        // data is Map(String, String) holding each value's serialized JsonNode, and tags is
        // Array(String) -- the two encodings this row shares with the span path.
        var datasetId = newDataset();
        var data = Map.of(
                "input", JsonUtils.getJsonNodeFromString("\"what is the capital of France?\""),
                "expected_output", JsonUtils.getJsonNodeFromString("{\"answer\": \"Paris\"}"));
        var item = newDatasetItem(data).toBuilder()
                .tags(java.util.Set.of("alpha", "beta"))
                .description("a description")
                .build();

        datasetResourceClient.createDatasetItems(
                DatasetItemBatch.builder().datasetId(datasetId).items(List.of(item)).build(),
                WORKSPACE_NAME, API_KEY);

        var actual = datasetResourceClient.getDatasetItem(item.id(), API_KEY, WORKSPACE_NAME);
        assertThat(actual.id()).isEqualTo(item.id());
        assertThat(actual.source()).isEqualTo(DatasetItemSource.MANUAL);
        assertThat(actual.data()).containsExactlyInAnyOrderEntriesOf(data);
        assertThat(actual.tags()).containsExactlyInAnyOrder("alpha", "beta");
        assertThat(actual.description()).isEqualTo("a description");
    }

    @Test
    @DisplayName("dataset item content with newlines and quotes does not split a JSONEachRow line")
    void datasetItemContentWithNewlinesSurvives() {
        var datasetId = newDataset();
        var awkward = JsonUtils.getJsonNodeFromString(
                "{\"text\": \"line one\\nline \\\"two\\\"\\ttabbed\", \"unicode\": \"\u65e5\u672c\u8a9e \u2014 ok\"}");
        var first = newDatasetItem(Map.of("input", awkward));
        var second = newDatasetItem(Map.of("input", JsonUtils.getJsonNodeFromString("\"plain\"")));

        datasetResourceClient.createDatasetItems(
                DatasetItemBatch.builder().datasetId(datasetId).items(List.of(first, second)).build(),
                WORKSPACE_NAME, API_KEY);

        // If the first row's newline ended its line early, the second would be lost or corrupt.
        assertThat(datasetResourceClient.getDatasetItem(first.id(), API_KEY, WORKSPACE_NAME).data())
                .containsEntry("input", awkward);
        assertThat(datasetResourceClient.getDatasetItem(second.id(), API_KEY, WORKSPACE_NAME).id())
                .isEqualTo(second.id());
    }

    @Test
    @DisplayName("a dataset item batch writes each row exactly once and server-stamps created_at and last_updated_at")
    void datasetItemBatchWritesEachRowOnceAndServerStampsCreatedAndLastUpdatedAt() {
        var datasetId = newDataset();
        var items = List.of(
                newDatasetItem(Map.of("input", JsonUtils.getJsonNodeFromString("\"one\""))),
                newDatasetItem(Map.of("input", JsonUtils.getJsonNodeFromString("\"two\""))),
                newDatasetItem(Map.of("input", JsonUtils.getJsonNodeFromString("\"three\""))));

        datasetResourceClient.createDatasetItems(
                DatasetItemBatch.builder().datasetId(datasetId).items(items).build(),
                WORKSPACE_NAME, API_KEY);

        var ids = items.stream().map(item -> "'" + item.id() + "'")
                .collect(java.util.stream.Collectors.joining(","));

        // Raw rows, no FINAL: reads collapse duplicates via LIMIT 1 BY, so a writer emitting every row
        // twice would still satisfy a per-id read. Cardinality is the only assertion that sees it.
        Long storedRows = queryOne(
                "SELECT count() AS row_count FROM dataset_item_versions WHERE workspace_id = '%s' AND id IN (%s)"
                        .formatted(WORKSPACE_ID, ids),
                row -> row.get("row_count", Long.class));
        assertThat(storedRows).isEqualTo(items.size());

        // created_at and last_updated_at are omitted from the JSON row so their DEFAULT now64(9)
        // stamps them. If they were sent as absent/zero instead, this would read back as the epoch --
        // and last_updated_at is the ReplacingMergeTree version, so a zero there would make every
        // later update lose to the original row.
        Long stampedRows = queryOne(
                ("SELECT count() AS row_count FROM dataset_item_versions WHERE workspace_id = '%s' "
                        + "AND id IN (%s) AND created_at > toDateTime64('2000-01-01 00:00:00', 9) "
                        + "AND last_updated_at > toDateTime64('2000-01-01 00:00:00', 9)")
                        .formatted(WORKSPACE_ID, ids),
                row -> row.get("row_count", Long.class));
        assertThat(stampedRows).isEqualTo(items.size());
    }
}
