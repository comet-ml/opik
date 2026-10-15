package com.comet.opik.domain.ailabels;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.SpanRow;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.TraceRow;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.ClickHouseDateTimeFormat;
import io.r2dbc.spi.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The renderer against ClickHouse, on the topology production runs mid-cutover: {@code traces} cut over to its
 * partitioned successor behind a {@code Distributed} wrapper, and {@code spans} first on its legacy layout, then cut
 * over the way the runbook does it, with and without the span_weeks bound. The same traces must render to the same
 * bytes on every layout, equal to rendering the rows the reads are expected to return.
 *
 * <p>Dedicated, non-reused containers: the cutover destructively swaps the live tables.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiLabelRendererClickHouseTest {

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String OTHER_WORKSPACE_ID = UUID.randomUUID().toString();
    private static final long MAX_FIELD_BYTES = 2_000;
    private static final int MAX_SPANS = 40;
    private static final long BUDGET = 6_000;
    /** Legacy spans keep nanoseconds; the successor and the render drop them. */
    private static final long SUB_MICROSECOND_NANOS = 999;
    private static final Instant EPOCH = Instant.EPOCH;

    private static final DatabaseAnalyticsDataModelConfig LEGACY_SPANS = DatabaseAnalyticsDataModelConfig.builder()
            .traceColumnsNonNullable(true)
            .tracesDistributedWrapEnabled(true)
            .deletionEventsInsertBatchSize(1_000)
            .build();
    private static final DatabaseAnalyticsDataModelConfig CUT_OVER_SPANS = LEGACY_SPANS.toBuilder()
            .spanColumnsNonNullable(true)
            .spansDistributedWrapEnabled(true)
            .build();
    private static final DatabaseAnalyticsDataModelConfig CUT_OVER_SPANS_WEEKS_BOUND = CUT_OVER_SPANS.toBuilder()
            .spanWeeksWriteEnabled(true)
            .spanWeeksReadEnabled(true)
            .build();

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);

    private final Random random = new Random(20261015);
    private final AiLabelRenderFixtures fixtures = new AiLabelRenderFixtures(20261015, true);
    private final AiLabelsConfig config = new AiLabelsConfig();

    private TransactionTemplateAsync template;
    private final Map<UUID, TraceRow> expectedTraces = new LinkedHashMap<>();
    private final Map<UUID, List<SpanRow>> expectedSpans = new LinkedHashMap<>();
    private final Map<UUID, UUID> projectByTrace = new LinkedHashMap<>();
    private final List<TraceRef> refs = new ArrayList<>();
    private Map<UUID, String> renderedOnLegacySpans;

    @BeforeAll
    void setUp() {
        Startables.deepStart(zookeeper, clickHouse).join();
        MigrationUtils.runClickhouseDbMigration(clickHouse);
        template = TransactionTemplateAsync.create(
                ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, DATABASE_NAME).build());
        config.setMaxFieldBytes(MAX_FIELD_BYTES);
        config.setMaxSpansPerTrace(MAX_SPANS);
        config.setRenderBudgetBytes(BUDGET);
        config.setRenderThreads(2);

        seed();
        // ai-labels runs only once traces are cut over, so they are from the start.
        cutOver("traces");
    }

    @AfterAll
    void tearDown() {
        clickHouse.stop();
        zookeeper.stop();
    }

    @Test
    @Order(1)
    void renderReadsTheLegacySpansLayout() {
        renderedOnLegacySpans = render(LEGACY_SPANS);

        assertThat(renderedOnLegacySpans).isEqualTo(expected());
        assertQueriesCarryTheirGuards();
    }

    @Test
    @Order(2)
    void renderIsByteIdenticalAcrossTheSpansCutover() {
        copySpansToSuccessor();
        cutOver("spans");
        registerSpanWeeks();

        assertThat(render(CUT_OVER_SPANS)).isEqualTo(renderedOnLegacySpans);
        assertThat(render(CUT_OVER_SPANS_WEEKS_BOUND)).isEqualTo(renderedOnLegacySpans);
    }

    /** A span whose week is not registered is out of the bound read and in the unbound one, so the bound applies. */
    @Test
    @Order(3)
    void renderBoundBySpanWeeksReadsOnlyRegisteredWeeks() {
        var trace = expectedTraces.values().stream()
                .filter(row -> expectedSpans.get(row.id()).isEmpty())
                .findFirst()
                .orElseThrow();
        var unregistered = fixtures.span(trace, null, 30).toBuilder()
                .id(uuidV7(AiLabelRenderFixtures.BASE_TIME.minus(60, ChronoUnit.DAYS)).toString())
                .build();
        insertSpans(WORKSPACE_ID, "spans_local", List.of(unregistered), Instant.now(), 0, true);
        var ref = refOf(trace);

        assertThat(render(CUT_OVER_SPANS, List.of(ref)))
                .isEqualTo(Map.of(trace.id(), expected(trace, List.of(unregistered))));
        assertThat(render(CUT_OVER_SPANS_WEEKS_BOUND, List.of(ref)))
                .isEqualTo(Map.of(trace.id(), expected(trace, List.of())));
    }

    /** The successor deletes by tombstone, which only the cut-over read knows to drop. */
    @Test
    @Order(4)
    void renderDropsASpanDeletedOnTheSuccessor() {
        var trace = expectedTraces.values().iterator().next();
        var spans = expectedSpans.get(trace.id());
        var deleted = spans.getLast();
        insertSpans(WORKSPACE_ID, "spans_local", List.of(deleted), Instant.now(), 1, true);

        assertThat(render(CUT_OVER_SPANS, List.of(refOf(trace))))
                .isEqualTo(Map.of(trace.id(), expected(trace, spans.subList(0, spans.size() - 1))));
    }

    private void seed() {
        var olderVersion = AiLabelRenderFixtures.BASE_TIME.plusSeconds(60);
        var latestVersion = olderVersion.plusSeconds(60);

        // A tree of mixed-script spans, some of them open, rendered at a budget that compacts it.
        var rich = fixtures.trace(400);
        rich = addTrace(rich, fixtures.spans(rich, 25, 6, 60));

        // An open trace with outsized fields, which the reads swap for a marker of their size.
        var outsized = fixtures.trace(30).toBuilder()
                .input("{\"blob\":\"" + "x".repeat((int) MAX_FIELD_BYTES) + "\"}")
                .errorInfo("{\"message\":\"" + "e".repeat((int) MAX_FIELD_BYTES) + "\"}")
                .endTime(null)
                .build();
        var outsizedSpans = new ArrayList<>(fixtures.spans(outsized, 3, 2, 20));
        outsizedSpans.set(1, outsizedSpans.get(1).toBuilder()
                .output("\"" + fixtures.text((int) MAX_FIELD_BYTES) + "\"")
                .errorInfo("{\"message\":\"" + "e".repeat((int) MAX_FIELD_BYTES) + "\"}")
                .build());
        addTrace(outsized, outsizedSpans);

        // More spans than the cap: the latest-starting ones are cut, a kept span's parent among them.
        var capped = fixtures.trace(30);
        var root = spanAt(capped, null, 0);
        var cutParent = spanAt(capped, null, 100);
        var cappedSpans = new ArrayList<>(List.of(root, cutParent, spanAt(capped, cutParent.id(), 39)));
        for (int i = 1; i <= 38; i++) {
            cappedSpans.add(spanAt(capped, root.id(), i));
        }
        for (int i = 101; i <= 104; i++) {
            cappedSpans.add(spanAt(capped, root.id(), i));
        }
        addTrace(capped, cappedSpans);

        // A trace and a span written twice: the latest version is read.
        var versioned = fixtures.trace(30);
        versioned = addTrace(versioned, fixtures.spans(versioned, 4, 2, 20));
        insertTraces(List.of(versioned.toBuilder().name("old name").build()), olderVersion, 0);
        insertSpans(WORKSPACE_ID, "spans", List.of(expectedSpans.get(versioned.id()).getFirst().toBuilder()
                .output("{\"old\":true}").build()), olderVersion, 0, false);

        // A trace in another project, without spans.
        addTrace(fixtures.trace(30).toBuilder().projectId(UUID.randomUUID()).build(), List.of());

        // A trace whose latest version is deleted yields nothing, spans or not.
        var deleted = fixtures.trace(30).toBuilder().id(uuidV7(AiLabelRenderFixtures.BASE_TIME)).build();
        projectByTrace.put(deleted.id(), deleted.projectId());
        refs.add(refOf(deleted));
        insertTraces(List.of(deleted), olderVersion, 0);
        insertTraces(List.of(deleted), latestVersion, 1);
        insertSpans(WORKSPACE_ID, "spans", withV7Ids(fixtures.spans(deleted, 3, 2, 20)), latestVersion, 0, false);

        // Another workspace's spans under a rendered trace id are never read.
        insertSpans(OTHER_WORKSPACE_ID, "spans", withV7Ids(fixtures.spans(rich, 2, 1, 20)), latestVersion, 0,
                false);

        insertTraces(List.copyOf(expectedTraces.values()), latestVersion, 0);
        var allSpans = new ArrayList<SpanRow>();
        expectedSpans.values().forEach(allSpans::addAll);
        Collections.shuffle(allSpans, random);
        insertSpans(WORKSPACE_ID, "spans", allSpans, latestVersion, 0, false);
    }

    /** Gives the trace and its spans UUIDv7 ids, as ingestion does, so the spans land in their real weeks. */
    private TraceRow addTrace(TraceRow trace, List<SpanRow> spans) {
        var stored = trace.toBuilder().id(uuidV7(trace.startTime())).build();
        var rows = withV7Ids(spans).stream()
                .map(span -> span.toBuilder().traceId(stored.id()).build())
                .toList();
        expectedTraces.put(stored.id(), stored);
        expectedSpans.put(stored.id(), rows);
        projectByTrace.put(stored.id(), stored.projectId());
        refs.add(refOf(stored));
        return stored;
    }

    private List<SpanRow> withV7Ids(List<SpanRow> spans) {
        Map<String, String> ids = new LinkedHashMap<>();
        spans.forEach(span -> ids.put(span.id(), uuidV7(span.startTime()).toString()));
        return spans.stream()
                .map(span -> span.toBuilder()
                        .id(ids.get(span.id()))
                        .parentSpanId(span.parentSpanId() == null ? null : ids.get(span.parentSpanId()))
                        .build())
                .toList();
    }

    private SpanRow spanAt(TraceRow trace, String parentSpanId, int second) {
        return fixtures.span(trace, parentSpanId, 10).toBuilder()
                .startTime(AiLabelRenderFixtures.BASE_TIME.plusSeconds(second))
                .build();
    }

    private Map<UUID, String> expected() {
        return expectedTraces.values().stream()
                .collect(Collectors.toMap(TraceRow::id, trace -> expected(trace, expectedSpans.get(trace.id()))));
    }

    /** What the reads should return, rendered: the SQL guard applied, and the earliest spans up to the cap. */
    private static String expected(TraceRow trace, List<SpanRow> spans) {
        var read = spans.stream()
                .sorted(Comparator.comparing(SpanRow::startTime).thenComparing(SpanRow::id))
                .limit(MAX_SPANS)
                .map(span -> span.toBuilder()
                        .input(guard(span.input()))
                        .output(guard(span.output()))
                        .metadata(guard(span.metadata()))
                        .errorInfo(guard(span.errorInfo()))
                        .build())
                .toList();
        var guardedTrace = trace.toBuilder()
                .input(guard(trace.input()))
                .output(guard(trace.output()))
                .metadata(guard(trace.metadata()))
                .errorInfo(guard(trace.errorInfo()))
                .build();
        return canonical(AiLabelRenderer.render(new TraceRef(trace.projectId(), trace.id(), trace.startTime()),
                guardedTrace, read, BUDGET));
    }

    private static String guard(String value) {
        if (value == null) {
            return null;
        }
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        return bytes > MAX_FIELD_BYTES ? "\"[%d bytes omitted]\"".formatted(bytes) : value;
    }

    private Map<UUID, String> render(DatabaseAnalyticsDataModelConfig dataModel) {
        return render(dataModel, refs);
    }

    private Map<UUID, String> render(DatabaseAnalyticsDataModelConfig dataModel, List<TraceRef> traces) {
        var renderer = new AiLabelRenderer(new AiLabelRenderDAO(template, dataModel, config), config);
        return renderer.render(WORKSPACE_ID, traces)
                .collectMap(outcome -> outcome.trace().traceId(), AiLabelRendererClickHouseTest::canonical)
                .block();
    }

    private static String canonical(RenderOutcome outcome) {
        return switch (outcome) {
            case RenderOutcome.Rendered rendered -> rendered.compactionLevel() + "\n" + rendered.bytes() + "\n"
                    + AiLabelRenderer.canonicalJson(rendered.state());
            case RenderOutcome.OverBudget overBudget -> "over_budget\n" + overBudget.bytes();
        };
    }

    private void assertQueriesCarryTheirGuards() {
        execute("SYSTEM FLUSH LOGS", statement -> {
        });
        var settings = template.nonTransaction(connection -> Flux.from(connection.createStatement("""
                SELECT DISTINCT Settings['max_threads'] AS max_threads, Settings['priority'] AS priority,
                    Settings['max_memory_usage'] AS max_memory_usage
                FROM system.query_log
                WHERE type = 'QueryFinish' AND log_comment LIKE :log_comment
                """).bind("log_comment", "ai-labels:render:" + WORKSPACE_ID + "%").execute())
                .flatMap(result -> result.map((row, metadata) -> List.of(row.get("max_threads", String.class),
                        row.get("priority", String.class), row.get("max_memory_usage", String.class))))
                .collectList())
                .block();

        assertThat(settings).containsExactly(List.of("2", "100", "2147483648"));
    }

    private void insertTraces(List<TraceRow> traces, Instant lastUpdatedAt, int isDeleted) {
        for (TraceRow trace : traces) {
            execute("""
                    INSERT INTO traces_local_v2 (id, workspace_id, project_id, name, start_time, end_time, input,
                        output, metadata, error_info, last_updated_at, is_deleted)
                    VALUES (:id, :workspace_id, :project_id, :name, :start_time, :end_time, :input, :output,
                        :metadata, :error_info, :last_updated_at, :is_deleted)
                    """, statement -> statement
                    .bind("id", trace.id().toString())
                    .bind("workspace_id", WORKSPACE_ID)
                    .bind("project_id", trace.projectId().toString())
                    .bind("name", orEmpty(trace.name()))
                    .bind("start_time", ClickHouseDateTimeFormat.formatNanos(trace.startTime()))
                    .bind("end_time", ClickHouseDateTimeFormat.formatNanos(
                            trace.endTime() == null ? EPOCH : trace.endTime()))
                    .bind("input", orEmpty(trace.input()))
                    .bind("output", orEmpty(trace.output()))
                    .bind("metadata", orEmpty(trace.metadata()))
                    .bind("error_info", orEmpty(trace.errorInfo()))
                    .bind("last_updated_at", ClickHouseDateTimeFormat.formatMicros(lastUpdatedAt))
                    .bind("is_deleted", isDeleted));
        }
    }

    /** Into the legacy {@code spans} (Nullable end_time, nanoseconds, no is_deleted) or the successor's shard. */
    private void insertSpans(String workspaceId, String table, List<SpanRow> spans, Instant lastUpdatedAt,
            int isDeleted, boolean successor) {
        for (SpanRow span : spans) {
            UUID projectId = projectByTrace.get(span.traceId());
            execute("""
                    INSERT INTO %s (id, workspace_id, project_id, trace_id, parent_span_id, name, type, start_time,
                        end_time, input, output, metadata, model, provider, error_info, last_updated_at
                        %s)
                    VALUES (:id, :workspace_id, :project_id, :trace_id, :parent_span_id, :name, :type, :start_time,
                        :end_time, :input, :output, :metadata, :model, :provider, :error_info, :last_updated_at
                        %s)
                    """.formatted(table, successor ? ", is_deleted" : "", successor ? ", :is_deleted" : ""),
                    statement -> {
                        statement.bind("id", span.id())
                                .bind("workspace_id", workspaceId)
                                .bind("project_id", projectId.toString())
                                .bind("trace_id", span.traceId().toString())
                                .bind("parent_span_id", orEmpty(span.parentSpanId()))
                                .bind("name", orEmpty(span.name()))
                                .bind("type", span.type())
                                .bind("start_time", ClickHouseDateTimeFormat.formatNanos(
                                        span.startTime().plusNanos(successor ? 0 : SUB_MICROSECOND_NANOS)))
                                .bind("input", orEmpty(span.input()))
                                .bind("output", orEmpty(span.output()))
                                .bind("metadata", orEmpty(span.metadata()))
                                .bind("model", orEmpty(span.model()))
                                .bind("provider", orEmpty(span.provider()))
                                .bind("error_info", orEmpty(span.errorInfo()))
                                .bind("last_updated_at", ClickHouseDateTimeFormat.formatMicros(lastUpdatedAt));
                        if (span.endTime() != null) {
                            statement.bind("end_time", ClickHouseDateTimeFormat.formatNanos(
                                    span.endTime().plusNanos(successor ? 0 : SUB_MICROSECOND_NANOS)));
                        } else if (successor) {
                            statement.bind("end_time", ClickHouseDateTimeFormat.formatNanos(EPOCH));
                        } else {
                            statement.bindNull("end_time", String.class);
                        }
                        if (successor) {
                            statement.bind("is_deleted", isDeleted);
                        }
                    });
        }
    }

    /** The runbook's backfill (data-migrations/spans-local-v2-cutover, 000001), over every row. */
    private void copySpansToSuccessor() {
        execute("""
                INSERT INTO spans_local_v2 (id, workspace_id, project_id, trace_id, parent_span_id, name, type,
                    start_time, end_time, input, output, metadata, tags, usage, created_at, last_updated_at,
                    created_by, last_updated_by, model, provider, total_estimated_cost, total_estimated_cost_version,
                    error_info, truncation_threshold, input_slim, output_slim, ttft, source, environment)
                SELECT id, workspace_id, project_id, trace_id,
                    if(length(parent_span_id) = 36, toFixedString(parent_span_id, 36), toFixedString('', 36)),
                    name, type, start_time, coalesce(end_time, toDateTime64('1970-01-01 00:00:00', 6)), input, output,
                    metadata, tags, usage, created_at, last_updated_at, created_by, last_updated_by, model, provider,
                    total_estimated_cost, total_estimated_cost_version, error_info, truncation_threshold, input_slim,
                    output_slim, coalesce(ttft, toFloat64('nan')), source, environment
                FROM spans
                """, statement -> {
        });
    }

    /** The runbook's exchange and Distributed wrap (000003_exchange_and_wrap.sql). */
    private void cutOver(String table) {
        execute("EXCHANGE TABLES %1$s AND %1$s_local_v2 ON CLUSTER '{cluster}'".formatted(table), statement -> {
        });
        execute("RENAME TABLE %1$s_local_v2 TO %1$s_pre_cutover_backup ON CLUSTER '{cluster}'".formatted(table),
                statement -> {
                });
        execute("""
                CREATE TABLE %1$s_dist ON CLUSTER '{cluster}' AS %1$s
                ENGINE = Distributed('{cluster}', '%2$s', '%1$s_local', sipHash64(project_id))
                """.formatted(table, DATABASE_NAME), statement -> {
        });
        execute("RENAME TABLE %1$s TO %1$s_local, %1$s_dist TO %1$s ON CLUSTER '{cluster}'".formatted(table),
                statement -> {
                });
    }

    /** The span weeks backfill ({@code SpanWeeksDAO}), over every span. */
    private void registerSpanWeeks() {
        execute("""
                INSERT INTO span_weeks (workspace_id, project_id, trace_id, id_week)
                SELECT workspace_id, project_id, trace_id,
                    toUInt32(toYYYYMMDD(toDate32(toDateTime64(UUIDv7ToDateTime(toUUID(id)), 0, 'UTC'))
                        - toIntervalDay(toDayOfWeek(toDateTime64(UUIDv7ToDateTime(toUUID(id)), 0, 'UTC'), 1)))) AS week
                FROM spans_local
                GROUP BY workspace_id, project_id, trace_id, week
                """, statement -> {
        });
    }

    private void execute(String sql, Consumer<Statement> binder) {
        template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            binder.accept(statement);
            return Mono.from(statement.execute()).flatMap(result -> Mono.from(result.getRowsUpdated())).then();
        }).block();
    }

    private TraceRef refOf(TraceRow trace) {
        return new TraceRef(trace.projectId(), trace.id(), trace.startTime());
    }

    /** A UUIDv7 at the given instant, so the row lands in that instant's weekly partition. */
    private UUID uuidV7(Instant at) {
        long mostSignificant = (at.toEpochMilli() << 16) | 0x7000L | (random.nextInt() & 0x0FFFL);
        long leastSignificant = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
