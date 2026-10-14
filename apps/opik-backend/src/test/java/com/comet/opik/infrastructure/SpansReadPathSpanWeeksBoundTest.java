package com.comet.opik.infrastructure;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every case of {@link SpansReadPathPartitionPruningTest} again, with the trace-keyed spans reads bound by the
 * {@code span_weeks} index: the same results and the same partitions read prove the index bound loses nothing the
 * span-key pre-pass found. Registration is asynchronous by design, so each write waits for its weeks to be indexed
 * before the case reads; that gap is accepted, not under test here.
 */
class SpansReadPathSpanWeeksBoundTest extends SpansReadPathPartitionPruningTest {

    private static final String INDEXED_WEEKS = """
            SELECT DISTINCT concat(toString(trace_id), ':', toString(id_week)) AS trace_week
            FROM span_weeks
            WHERE trace_id IN :trace_ids
            """;

    /** The tables the latest statement of an op read. */
    private static final String TABLES_READ = """
            SELECT arrayStringConcat(tables, ',')
            FROM system.query_log
            WHERE log_comment LIKE concat(:op, ':%')
            AND type = 'QueryFinish'
            AND query LIKE concat('%', :id, '%')
            ORDER BY event_time_microseconds DESC
            LIMIT 1
            """;

    @Override
    protected List<CustomConfig> extraConfigs() {
        return List.of(
                new CustomConfig("databaseAnalyticsDataModel.spanWeeksWriteEnabled", "true"),
                new CustomConfig("databaseAnalyticsDataModel.spanWeeksReadEnabled", "true"));
    }

    @Override
    protected SpanResourceClient newSpanResourceClient(ClientSupport clientSupport, String baseUrl) {
        return new SpanResourceClient(clientSupport, baseUrl) {
            @Override
            public void batchCreateSpans(List<Span> spans, String apiKey, String workspaceName) {
                super.batchCreateSpans(spans, apiKey, workspaceName);
                awaitIndexed(spans.stream().map(span -> traceWeek(span.traceId(), span.id())).toList());
            }

            @Override
            public UUID createSpan(Span span, String apiKey, String workspaceName) {
                var id = super.createSpan(span, apiKey, workspaceName);
                awaitIndexed(List.of(traceWeek(span.traceId(), id)));
                return id;
            }

            @Override
            public void updateSpan(UUID spanId, SpanUpdate spanUpdate, String apiKey, String workspaceName) {
                super.updateSpan(spanId, spanUpdate, apiKey, workspaceName);
                awaitIndexed(List.of(traceWeek(spanUpdate.traceId(), spanId)));
            }
        };
    }

    @Test
    void traceByIdReadsItsSpansThroughTheIndex() {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(16);
        var traceId = traceResourceClient.createTrace(factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .build(), API_KEY, WORKSPACE_NAME);
        spanResourceClient.batchCreateSpans(List.of(newSpan(Instant.now(), traceId).toBuilder()
                .projectName(projectName).build()), API_KEY, WORKSPACE_NAME);

        traceResourceClient.getById(traceId, WORKSPACE_NAME, API_KEY);

        assertSpanWeeksRead("find_traces_by_ids", traceId);
    }

    @Override
    protected void assertSpanWeeksRead(String op, UUID id) {
        assertThat(tablesRead(op, id)).as("tables read by %s", op).contains("%s.span_weeks".formatted(DATABASE_NAME));
    }

    /** Where ids past 2300 are stored, as the parent's past-ceiling cases also expect. */
    private static final long PAST_CEILING_WEEK = 22991225L;

    /** The span's week from its own v7 timestamp, independently of the production week calculation. */
    private static String traceWeek(UUID traceId, UUID spanId) {
        var monday = LocalDate.ofInstant(Instant.ofEpochMilli(spanId.getMostSignificantBits() >>> 16), ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var week = Math.min(Long.parseLong(monday.format(DateTimeFormatter.BASIC_ISO_DATE)), PAST_CEILING_WEEK);
        return "%s:%d".formatted(traceId, week);
    }

    private void awaitIndexed(Collection<String> traceWeeks) {
        var traceIds = traceWeeks.stream().map(traceWeek -> traceWeek.substring(0, traceWeek.indexOf(':')))
                .collect(Collectors.toSet());
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> indexedWeeks(traceIds).containsAll(traceWeeks));
    }

    private Set<String> indexedWeeks(Set<String> traceIds) {
        return template.nonTransaction(connection -> Flux.from(connection.createStatement(INDEXED_WEEKS)
                .bind("trace_ids", traceIds.toArray(String[]::new))
                .execute())
                .flatMap(result -> result.map((row, metadata) -> row.get("trace_week", String.class)))
                .collect(Collectors.toSet()))
                .block();
    }

    private String tablesRead(String op, UUID id) {
        return Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> template.nonTransaction(connection -> Mono.from(connection.createStatement(TABLES_READ)
                        .bind("op", op)
                        .bind("id", id.toString())
                        .execute())
                        .flatMap(result -> Mono.from(result.map((row, metadata) -> row.get(0, String.class)))))
                        .block(), tables -> tables != null);
    }
}
