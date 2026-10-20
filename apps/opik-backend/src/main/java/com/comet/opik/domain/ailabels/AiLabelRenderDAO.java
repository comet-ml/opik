package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.utils.SentinelTranslation.emptyUuidToNull;

/**
 * The renderer's reads: a fixed projection of traces, of their spans and of their threads' earlier turns, latest
 * version per id. Each one scopes to a single workspace and runs with a small thread cap, a background priority, a
 * memory cap and a time cap ({@code renderMaxExecutionTime}).
 *
 * <p>An input or output value over {@code maxFieldBytes} is swapped in SQL for a JSON string naming its size, so it
 * never reaches the JVM. Cutting it instead would leave invalid JSON.
 *
 * <p>Reads only the {@code spans} name, never {@code spans_local_v2}, which lags live traffic. The spans cutover is in
 * progress, so both layouts are read and both come back the same: sub-microsecond digits are dropped, since the
 * successor stores microseconds.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AiLabelRenderDAO {

    private static final String LOG_COMMENT = "ai-labels:render";

    private static final String FIND_TRACES = """
            SELECT id, project_id, thread_id, guarded_name AS name, guarded_input AS input, guarded_output AS output
            FROM (
                SELECT
                    id,
                    project_id,
                    thread_id,
                    if(length(name) > :max_field_bytes,
                        concat('[', toString(length(name)), ' bytes omitted]'), name) AS guarded_name,
                    if(length(input) > :max_field_bytes,
                        concat('"[', toString(length(input)), ' bytes omitted]"'), input) AS guarded_input,
                    if(length(output) > :max_field_bytes,
                        concat('"[', toString(length(output)), ' bytes omitted]"'), output) AS guarded_output,
                    is_deleted
                FROM traces
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                AND id IN :ids
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            WHERE is_deleted = 0
            SETTINGS log_comment = '<log_comment>', max_threads = 2, priority = 100, max_memory_usage = 2147483648,
                max_execution_time = <max_execution_time>
            """;

    private static final String SPAN_FILTER = """
            WHERE workspace_id = :workspace_id
            AND project_id IN :project_ids
            AND trace_id IN :trace_ids
            <if(span_weeks_bound)>AND toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1))) IN (
                SELECT id_week FROM span_weeks
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                AND trace_id IN :trace_ids
            )<endif>
            """;

    /**
     * The spans a render reads, narrow, one row each: the latest version of each span, not deleted; the earliest
     * {@code max_spans} of each trace; and of those, the ones within {@code max_trace_bytes} of name, input and output
     * in start order (the first is always kept). {@code bytes} counts what the content read returns, an outsized field
     * as its marker ({@code "[N bytes omitted]"}, 18 bytes and N's digits; 16 for a name, which is unquoted). Only string lengths are read, never the content. Start order is in microseconds, so the cut is the
     * same on both layouts.
     */
    private static final String SELECTED_SPANS = """
            SELECT trace_id, id, last_updated_at, bytes
            FROM (
                SELECT trace_id, id, last_updated_at, bytes,
                    sum(bytes) OVER (PARTITION BY trace_id ORDER BY start_order, id ROWS UNBOUNDED PRECEDING)
                        AS trace_bytes
                FROM (
                    SELECT trace_id, id, last_updated_at, bytes, toDateTime64(start_time, 6) AS start_order
                    FROM (
                        SELECT trace_id, id, start_time, last_updated_at,
                            if(length(name) > :max_field_bytes, 16 + length(toString(length(name))), length(name))
                                + if(length(input) > :max_field_bytes, 18 + length(toString(length(input))),
                                    length(input))
                                + if(length(output) > :max_field_bytes, 18 + length(toString(length(output))),
                                    length(output)) AS bytes
                            <if(spans_v2)>, is_deleted<endif>
                        FROM spans
                        """ + SPAN_FILTER + """
                        ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    )
                    <if(spans_v2)>WHERE is_deleted = 0<endif>
                    ORDER BY trace_id, start_order, id
                    LIMIT :max_spans BY trace_id
                )
            )
            WHERE trace_bytes \\<= :max_trace_bytes OR trace_bytes = bytes
            """;

    /**
     * The selected spans, each with the key the content read picks it by and the bytes of input and output it returns:
     * what reads are batched by.
     */
    private static final String FIND_SELECTED_SPANS = """
            SELECT trace_id, concat(id, ':', toString(toUnixTimestamp64Micro(last_updated_at))) AS span_key, bytes
            FROM (""" + SELECTED_SPANS + """
            )
            SETTINGS log_comment = '<log_comment>', max_threads = 2, priority = 100, max_memory_usage = 2147483648,
                max_execution_time = <max_execution_time>
            """;

    /**
     * The selected spans' content, ordered by trace so the caller can stream one trace at a time. The order is a prefix
     * of both layouts' sort keys, so it is read in order rather than sorted, and small blocks keep the wide rows from
     * piling up in memory. Rows come in no order within a trace; the tree orders siblings. The spans are picked by the
     * keys {@link #FIND_SELECTED_SPANS} returned: selecting them again in a subquery here reads the content columns
     * twice (ClickHouse 26.3).
     */
    private static final String FIND_SPANS = """
            SELECT trace_id, id, parent_span_id,
                if(length(name) > :max_field_bytes,
                    concat('[', toString(length(name)), ' bytes omitted]'), name) AS name,
                type,
                if(length(input) > :max_field_bytes,
                    concat('"[', toString(length(input)), ' bytes omitted]"'), input) AS input,
                if(length(output) > :max_field_bytes,
                    concat('"[', toString(length(output)), ' bytes omitted]"'), output) AS output,
                start_time
            FROM spans
            """ + SPAN_FILTER + """
            AND concat(id, ':', toString(toUnixTimestamp64Micro(last_updated_at))) IN :span_keys
            ORDER BY workspace_id, project_id, trace_id
            SETTINGS log_comment = '<log_comment>', max_threads = 2, priority = 100, max_memory_usage = 2147483648,
                max_execution_time = <max_execution_time>,
                max_block_size = 64
            """;

    /**
     * The earlier turns of the given threads with ids in [{@code min_id}, {@code max_id}). Not
     * {@code truncated_input}: it is a byte prefix, and a chat input's newest message is at its end.
     */
    private static final String FIND_THREAD_TURNS = """
            SELECT id, project_id, thread_id, guarded_input AS input, guarded_output AS output
            FROM (
                SELECT
                    id,
                    project_id,
                    thread_id,
                    if(length(input) > :max_field_bytes,
                        concat('"[', toString(length(input)), ' bytes omitted]"'), input) AS guarded_input,
                    if(length(output) > :max_field_bytes,
                        concat('"[', toString(length(output)), ' bytes omitted]"'), output) AS guarded_output,
                    is_deleted
                FROM traces
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                AND thread_id IN :thread_ids
                AND id >= :min_id
                AND id \\< :max_id
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            WHERE is_deleted = 0
            SETTINGS log_comment = '<log_comment>', max_threads = 2, priority = 100, max_memory_usage = 2147483648,
                max_execution_time = <max_execution_time>
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull @Config("databaseAnalyticsDataModel") DatabaseAnalyticsDataModelConfig dataModelConfig;
    private final @NonNull @Config("aiLabels") AiLabelsConfig config;

    @Builder(toBuilder = true)
    record TraceRow(
            @NonNull UUID id,
            @NonNull UUID projectId,
            @Nullable String threadId,
            @Nullable String name,
            @Nullable String input,
            @Nullable String output) {
    }

    /**
     * Span ids stay strings: the tree only compares them, and a malformed one must not fail the whole read. A root span
     * has no {@code parentSpanId}; the other nullable fields are null when the span never set them.
     */
    @Builder(toBuilder = true)
    record SpanRow(
            @NonNull UUID traceId,
            @NonNull String id,
            @Nullable String parentSpanId,
            @Nullable String name,
            @Nullable String type,
            @Nullable String input,
            @Nullable String output,
            @Nullable Instant startTime) {
    }

    /** {@code key} is opaque: {@link #findSpans} takes it back. */
    @Builder(toBuilder = true)
    record SelectedSpan(@NonNull UUID traceId, @NonNull String key, long bytes) {
    }

    /** One earlier turn of a thread, as stored: its input and output are JSON text. */
    @Builder(toBuilder = true)
    record ThreadTurnRow(
            @NonNull UUID id,
            @NonNull UUID projectId,
            @NonNull String threadId,
            @Nullable String input,
            @Nullable String output) {
    }

    Flux<TraceRow> findTraces(@NonNull String workspaceId, @NonNull List<TraceRef> traces) {
        return asyncTemplate.stream(connection -> {
            ST template = getSTWithLogComment(FIND_TRACES, LOG_COMMENT, workspaceId, null,
                    "traces:%d".formatted(traces.size()))
                    .add("max_execution_time", config.getRenderMaxExecutionTime().toSeconds());
            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("project_ids", projectIds(traces))
                    .bind("ids", traces.stream().map(trace -> trace.traceId().toString()).toArray(String[]::new))
                    .bind("max_field_bytes", config.getMaxFieldBytes());
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> TraceRow.builder()
                            .id(row.get("id", UUID.class))
                            .projectId(row.get("project_id", UUID.class))
                            .threadId(StringUtils.defaultIfBlank(row.get("thread_id", String.class), null))
                            .name(row.get("name", String.class))
                            .input(row.get("input", String.class))
                            .output(row.get("output", String.class))
                            .build()));
        });
    }

    Flux<SelectedSpan> findSelectedSpans(@NonNull String workspaceId, @NonNull List<TraceRef> traces) {
        return asyncTemplate.stream(connection -> Flux
                .from(spanStatement(connection, FIND_SELECTED_SPANS, workspaceId, traces)
                        .bind("max_spans", config.getMaxSpansPerTrace())
                        .bind("max_trace_bytes", config.getMaxSpanBytesPerTrace())
                        .execute())
                .flatMap(result -> result.map((row, rowMetadata) -> SelectedSpan.builder()
                        .traceId(row.get("trace_id", UUID.class))
                        .key(row.get("span_key", String.class))
                        .bytes(row.get("bytes", Long.class))
                        .build())));
    }

    /** The content of the given selected spans of the traces, grouped by trace. */
    Flux<SpanRow> findSpans(@NonNull String workspaceId, @NonNull List<TraceRef> traces,
            @NonNull List<SelectedSpan> spans) {
        return asyncTemplate.stream(connection -> Flux
                .from(spanStatement(connection, FIND_SPANS, workspaceId, traces)
                        .bind("span_keys", spans.stream().map(SelectedSpan::key).toArray(String[]::new))
                        .execute())
                .flatMap(result -> result.map((row, rowMetadata) -> SpanRow.builder()
                        .traceId(row.get("trace_id", UUID.class))
                        .id(row.get("id", String.class))
                        // The successor's FixedString(36) reads an absent parent back as 36 NUL characters.
                        .parentSpanId(emptyUuidToNull(row.get("parent_span_id", String.class)))
                        .name(row.get("name", String.class))
                        .type(row.get("type", String.class))
                        .input(row.get("input", String.class))
                        .output(row.get("output", String.class))
                        .startTime(instant(row, "start_time"))
                        .build())));
    }

    private Statement spanStatement(Connection connection, String query, String workspaceId,
            List<TraceRef> traces) {
        ST template = getSTWithLogComment(query, LOG_COMMENT, workspaceId, null, "traces:%d".formatted(traces.size()))
                .add("max_execution_time", config.getRenderMaxExecutionTime().toSeconds());
        // The successor has is_deleted; the legacy table hard-deletes. The flag flips with the EXCHANGE that puts the
        // successor behind the name, as SpanDAO and TraceDAO read it.
        if (dataModelConfig.spanColumnsNonNullable()) {
            template.add("spans_v2", true);
        }
        if (dataModelConfig.spanWeeksReadBounded()) {
            template.add("span_weeks_bound", true);
        }
        return connection.createStatement(template.render())
                .bind("workspace_id", workspaceId)
                .bind("project_ids", projectIds(traces))
                .bind("trace_ids", traces.stream().map(trace -> trace.traceId().toString()).toArray(String[]::new))
                .bind("max_field_bytes", config.getMaxFieldBytes());
    }

    /**
     * The traces of {@code threadIds} in {@code projectIds} with ids from {@code minId} up to, not including,
     * {@code maxId}. UUIDv7 ids sort by time, so the range is a time range on the primary key.
     */
    Flux<ThreadTurnRow> findThreadTurns(@NonNull String workspaceId, @NonNull Set<UUID> projectIds,
            @NonNull Set<String> threadIds, @NonNull UUID minId, @NonNull UUID maxId) {
        return asyncTemplate.stream(connection -> {
            ST template = getSTWithLogComment(FIND_THREAD_TURNS, LOG_COMMENT, workspaceId, null,
                    "threads:%d".formatted(threadIds.size()))
                    .add("max_execution_time", config.getRenderMaxExecutionTime().toSeconds());
            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("project_ids", projectIds.stream().map(UUID::toString).toArray(String[]::new))
                    .bind("thread_ids", threadIds.toArray(String[]::new))
                    .bind("min_id", minId.toString())
                    .bind("max_id", maxId.toString())
                    // The renderer cuts each turn's fields to history.maxFieldBytes, so SQL must not drop less.
                    .bind("max_field_bytes",
                            Math.max(config.getMaxFieldBytes(), config.getHistory().getMaxFieldBytes()));
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> ThreadTurnRow.builder()
                            .id(row.get("id", UUID.class))
                            .projectId(row.get("project_id", UUID.class))
                            .threadId(row.get("thread_id", String.class))
                            .input(row.get("input", String.class))
                            .output(row.get("output", String.class))
                            .build()));
        });
    }

    private static String[] projectIds(List<TraceRef> traces) {
        return traces.stream().map(trace -> trace.projectId().toString()).distinct().toArray(String[]::new);
    }

    private static Instant instant(Row row, String column) {
        var value = row.get(column, Instant.class);
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }
}
