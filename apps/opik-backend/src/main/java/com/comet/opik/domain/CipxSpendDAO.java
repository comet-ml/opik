package com.comet.opik.domain;

import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.comet.opik.utils.template.TemplateUtils;
import com.fasterxml.jackson.databind.JsonNode;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

/**
 * Writes the cipx_spends table from cipx LLM-call spans: span-level call data only (model + usage
 * counters); the blocks land in cipx_spend_blocks via {@link CipxSpendBlockDAO}. Triggered
 * asynchronously off span create events; never reads the spans or cipx_spends tables. The cipx fields
 * are parsed from metadata in Java ({@link SpanRow#from}); the listener only passes rows it has
 * already gated to cipx.
 *
 * <p>This is a plain INSERT: ingestion is create-only (cipx data is complete on the create event and
 * immutable), so the ReplacingMergeTree is only a safeguard against replayed events — a replay
 * produces the same sorting key and dedups at merge time. last_updated_at is left to the column
 * DEFAULT now64(6). project_id must be non-empty for the row to land under the correct key — callers
 * must drop blank rows before calling insert (the listener does).
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class CipxSpendDAO {

    /** A cipx_spends row constructed from a span's metadata. */
    @Builder(toBuilder = true)
    public record SpanRow(
            @NonNull String spanId,
            @NonNull String traceId,
            @NonNull String projectId,
            @NonNull Instant startTime,
            @NonNull String model,
            long uInput,
            long uCacheRead,
            long uCacheCreation,
            long uOutput) {

        public static SpanRow from(UUID spanId, UUID traceId, UUID projectId, JsonNode metadata, Instant startTime) {
            JsonNode call = metadata.path("cipx").path("call");
            JsonNode usage = call.path("usage");
            return SpanRow.builder()
                    .spanId(spanId.toString())
                    .traceId(traceId.toString())
                    .projectId(projectId != null ? projectId.toString() : "")
                    .startTime(startTime)
                    .model(call.path("model").asText(""))
                    .uInput(usage.path("input_tokens").asLong(0))
                    .uCacheRead(usage.path("cache_read_input_tokens").asLong(0))
                    .uCacheCreation(usage.path("cache_creation_input_tokens").asLong(0))
                    .uOutput(usage.path("output_tokens").asLong(0))
                    .build();
        }
    }

    // One tuple per row (mirrors SpanDAO.BULK_INSERT). start_time is bound from Java (the source
    // span's stored start).
    private static final String INSERT = """
            INSERT INTO cipx_spends
                (workspace_id, project_id, trace_id, span_id, start_time, model,
                 u_input, u_cache_read, u_cache_creation, u_output)
            SETTINGS log_comment = '<log_comment>'
            FORMAT Values
                <items:{item |
                    (
                        :workspace_id,
                        :project_id<item.index>,
                        :trace_id<item.index>,
                        :span_id<item.index>,
                        :start_time<item.index>,
                        :model<item.index>,
                        :u_input<item.index>,
                        :u_cache_read<item.index>,
                        :u_cache_creation<item.index>,
                        :u_output<item.index>
                    )
                    <if(item.hasNext)>,<endif>
                }>
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    public Mono<Long> insert(@NonNull List<SpanRow> rows, @NonNull String workspaceId, @NonNull String userName) {
        if (rows.isEmpty()) {
            return Mono.just(0L);
        }
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> insert(rows, workspaceId, userName, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    private Publisher<? extends Result> insert(List<SpanRow> rows, String workspaceId, String userName,
            Connection connection) {
        List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(rows.size());
        ST template = getSTWithLogComment(INSERT, "insert_cipx_spends", workspaceId, userName, rows.size());
        template.add("items", queryItems);
        Statement statement = connection.createStatement(template.render());

        // Positional binds: the driver resolves named binds with a linear indexOf over the statement's
        // parameter list (quadratic per statement), while bind(int) is a direct array write. Indices
        // follow the placeholders' first-appearance order in the rendered SQL: workspace_id once at 0
        // (repeats dedup), then 9 parameters per row tuple in template order.
        statement.bind(0, workspaceId);
        int index = 1;
        for (SpanRow row : rows) {
            statement.bind(index++, row.projectId())
                    .bind(index++, row.traceId())
                    .bind(index++, row.spanId())
                    .bind(index++, ClickHouseDateTimeFormat.formatNanos(row.startTime()))
                    .bind(index++, row.model())
                    .bind(index++, row.uInput())
                    .bind(index++, row.uCacheRead())
                    .bind(index++, row.uCacheCreation())
                    .bind(index++, row.uOutput());
        }

        return statement.execute();
    }
}
