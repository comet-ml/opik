package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import io.r2dbc.spi.Row;
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
import java.util.UUID;

import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.utils.SentinelTranslation.emptyUuidToNull;
import static com.comet.opik.utils.SentinelTranslation.epochToNull;

/**
 * The renderer's reads: a fixed projection of traces and of their spans, latest version per id. Each one scopes to a
 * single workspace and runs with a small thread cap, a background priority and a memory cap.
 *
 * <p>An input, output or metadata value over {@code maxFieldBytes} is swapped in SQL for a JSON string naming its
 * size, so it never reaches the JVM. Cutting it instead would leave invalid JSON.
 *
 * <p>Reads only the {@code spans} name, never {@code spans_local_v2}, which lags live traffic. The spans cutover is in
 * progress, so both layouts are read and both come back the same: sub-microsecond digits are dropped (the successor
 * stores microseconds), and an open {@code end_time}, null on the legacy table and the epoch on the successor, is null.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AiLabelRenderDAO {

    private static final String LOG_COMMENT = "ai-labels:render";

    private static final String FIND_TRACES = """
            SELECT id, project_id, guarded_name AS name, guarded_input AS input, guarded_output AS output,
                guarded_metadata AS metadata, start_time, end_time, guarded_error_info AS error_info
            FROM (
                SELECT
                    id,
                    project_id,
                    if(length(name) > :max_field_bytes,
                        concat('[', toString(length(name)), ' bytes omitted]'), name) AS guarded_name,
                    if(length(input) > :max_field_bytes,
                        concat('"[', toString(length(input)), ' bytes omitted]"'), input) AS guarded_input,
                    if(length(output) > :max_field_bytes,
                        concat('"[', toString(length(output)), ' bytes omitted]"'), output) AS guarded_output,
                    if(length(metadata) > :max_field_bytes,
                        concat('"[', toString(length(metadata)), ' bytes omitted]"'), metadata) AS guarded_metadata,
                    start_time,
                    end_time,
                    if(length(error_info) > :max_field_bytes,
                        concat('"[', toString(length(error_info)), ' bytes omitted]"'), error_info) AS guarded_error_info,
                    is_deleted
                FROM traces
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                AND id IN :ids
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            WHERE is_deleted = 0
            SETTINGS log_comment = '<log_comment>', max_threads = 2, priority = 100, max_memory_usage = 2147483648
            """;

    /**
     * Ordered by trace so the caller can stream one trace at a time. The cut at {@code max_spans} keeps the earliest
     * spans; it orders by microseconds so the cut is the same on both layouts.
     */
    private static final String FIND_SPANS = """
            SELECT trace_id, id, parent_span_id, guarded_name AS name, type, guarded_input AS input, guarded_output AS output,
                guarded_metadata AS metadata, start_time, end_time, model, provider, guarded_error_info AS error_info
            FROM (
                SELECT
                    trace_id,
                    id,
                    parent_span_id,
                    if(length(name) > :max_field_bytes,
                        concat('[', toString(length(name)), ' bytes omitted]'), name) AS guarded_name,
                    type,
                    if(length(input) > :max_field_bytes,
                        concat('"[', toString(length(input)), ' bytes omitted]"'), input) AS guarded_input,
                    if(length(output) > :max_field_bytes,
                        concat('"[', toString(length(output)), ' bytes omitted]"'), output) AS guarded_output,
                    if(length(metadata) > :max_field_bytes,
                        concat('"[', toString(length(metadata)), ' bytes omitted]"'), metadata) AS guarded_metadata,
                    start_time,
                    end_time,
                    model,
                    provider,
                    if(length(error_info) > :max_field_bytes,
                        concat('"[', toString(length(error_info)), ' bytes omitted]"'), error_info) AS guarded_error_info
                    <if(spans_v2)>, is_deleted<endif>
                FROM spans
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                AND trace_id IN :trace_ids
                <if(span_weeks_bound)>AND toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1))) IN (
                    SELECT id_week FROM span_weeks
                    WHERE workspace_id = :workspace_id
                    AND project_id IN :project_ids
                    AND trace_id IN :trace_ids
                )<endif>
                ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            <if(spans_v2)>WHERE is_deleted = 0<endif>
            ORDER BY trace_id, toDateTime64(start_time, 6), id
            LIMIT :max_spans BY trace_id
            SETTINGS log_comment = '<log_comment>', max_threads = 2, priority = 100, max_memory_usage = 2147483648
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull @Config("databaseAnalyticsDataModel") DatabaseAnalyticsDataModelConfig dataModelConfig;
    private final @NonNull @Config("aiLabels") AiLabelsConfig config;

    @Builder(toBuilder = true)
    record TraceRow(
            @NonNull UUID id,
            @NonNull UUID projectId,
            String name,
            String input,
            String output,
            String metadata,
            Instant startTime,
            Instant endTime,
            String errorInfo) {
    }

    /** Span ids stay strings: the tree only compares them, and a malformed one must not fail the whole read. */
    @Builder(toBuilder = true)
    record SpanRow(
            @NonNull UUID traceId,
            @NonNull String id,
            String parentSpanId,
            String name,
            String type,
            String input,
            String output,
            String metadata,
            Instant startTime,
            Instant endTime,
            String model,
            String provider,
            String errorInfo) {
    }

    Flux<TraceRow> findTraces(@NonNull String workspaceId, @NonNull List<TraceRef> traces) {
        return asyncTemplate.stream(connection -> {
            ST template = getSTWithLogComment(FIND_TRACES, LOG_COMMENT, workspaceId, null,
                    "traces:%d".formatted(traces.size()));
            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("project_ids", projectIds(traces))
                    .bind("ids", traces.stream().map(trace -> trace.traceId().toString()).toArray(String[]::new))
                    .bind("max_field_bytes", config.getMaxFieldBytes());
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> TraceRow.builder()
                            .id(row.get("id", UUID.class))
                            .projectId(row.get("project_id", UUID.class))
                            .name(row.get("name", String.class))
                            .input(row.get("input", String.class))
                            .output(row.get("output", String.class))
                            .metadata(row.get("metadata", String.class))
                            .startTime(instant(row, "start_time"))
                            .endTime(epochToNull(instant(row, "end_time")))
                            .errorInfo(row.get("error_info", String.class))
                            .build()));
        });
    }

    /** The traces' spans, grouped by trace and in start order within it, at most {@code maxSpansPerTrace} each. */
    Flux<SpanRow> findSpans(@NonNull String workspaceId, @NonNull List<TraceRef> traces) {
        return asyncTemplate.stream(connection -> {
            ST template = getSTWithLogComment(FIND_SPANS, LOG_COMMENT, workspaceId, null,
                    "traces:%d".formatted(traces.size()));
            // The successor has is_deleted; the legacy table hard-deletes. The flag flips with the EXCHANGE that puts
            // the successor behind the name, as SpanDAO and TraceDAO read it.
            if (dataModelConfig.spanColumnsNonNullable()) {
                template.add("spans_v2", true);
            }
            if (dataModelConfig.spanWeeksReadBounded()) {
                template.add("span_weeks_bound", true);
            }
            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("project_ids", projectIds(traces))
                    .bind("trace_ids", traces.stream().map(trace -> trace.traceId().toString())
                            .toArray(String[]::new))
                    .bind("max_field_bytes", config.getMaxFieldBytes())
                    .bind("max_spans", config.getMaxSpansPerTrace());
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> SpanRow.builder()
                            .traceId(row.get("trace_id", UUID.class))
                            .id(row.get("id", String.class))
                            // The successor's FixedString(36) reads an absent parent back as 36 NUL characters.
                            .parentSpanId(emptyUuidToNull(row.get("parent_span_id", String.class)))
                            .name(row.get("name", String.class))
                            .type(row.get("type", String.class))
                            .input(row.get("input", String.class))
                            .output(row.get("output", String.class))
                            .metadata(row.get("metadata", String.class))
                            .startTime(instant(row, "start_time"))
                            .endTime(epochToNull(instant(row, "end_time")))
                            .model(StringUtils.defaultIfBlank(row.get("model", String.class), null))
                            .provider(StringUtils.defaultIfBlank(row.get("provider", String.class), null))
                            .errorInfo(row.get("error_info", String.class))
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
