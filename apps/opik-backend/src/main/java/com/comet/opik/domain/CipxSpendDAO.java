package com.comet.opik.domain;

import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * Writes the cipx_spends table from cipx LLM-call spans. Triggered asynchronously off span
 * create/update events; never reads the spans or cipx_spends tables. The cipx fields are parsed from
 * metadata in Java ({@link SpanRow#from}); the listener only passes rows it has already gated to cipx.
 *
 * <p>This is a plain INSERT: the incoming row is complete (cipx metadata is wholesale, project_id is
 * the resolved id, start_time is the span's real start on create and the UUIDv7-embedded time on
 * update), so the ReplacingMergeTree merges by its sorting key — a create followed by an update
 * produces one row (latest last_updated_at wins on FINAL reads), with no self-merge needed.
 * last_updated_at is left to the column DEFAULT now64(6).
 * project_id must be non-empty for the row to land under the correct key, so blank rows are dropped.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class CipxSpendDAO {

    /** A cipx_spends row constructed from a span's metadata. blocksJson is the cipx.blocks array with
     *  identity_context blocks already dropped in Java. */
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
            long uOutput,
            @NonNull String blocksJson) {

        public static SpanRow from(UUID spanId, UUID traceId, UUID projectId, JsonNode metadata, Instant startTime) {
            JsonNode call = metadata.path("cipx").path("call");
            JsonNode usage = call.path("usage");
            JsonNode blocks = metadata.path("cipx").path("blocks");
            ArrayNode kept = JsonUtils.createArrayNode();
            if (blocks.isArray()) {
                for (JsonNode block : blocks) {
                    boolean identity = "identity_context".equals(block.path("category").asText())
                            && "identity_context".equals(block.path("parent_category").asText());
                    if (!identity) {
                        kept.add(block);
                    }
                }
            }
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
                    .blocksJson(kept.toString())
                    .build();
        }
    }

    // One tuple per row (mirrors SpanDAO.BULK_INSERT). start_time is bound from Java (the span's real
    // start on create, the UUIDv7-embedded time on update); blocks is bound as the pre-filtered JSON string
    // and parsed into the typed Array(Tuple) by ClickHouse. The r2dbc driver inlines bound values into the
    // FORMAT Values text, so a native Array(Tuple) can't be bound here — JSONExtract of one JSON literal is
    // what the Values parser accepts. The exact metadata->column mapping (this projection) is the initial
    // extraction, finalized later.
    private static final String INSERT = """
            INSERT INTO cipx_spends
                (workspace_id, project_id, trace_id, span_id, start_time, model,
                 u_input, u_cache_read, u_cache_creation, u_output, blocks)
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
                        :u_output<item.index>,
                        JSONExtract(:blocks_json<item.index>, 'Array(Tuple(category String, side String, cache_status String, parent_category String, chars Int64, tool_name String, tool_server String, tool_use_id String, resource String, kind String))')
                    )
                    <if(item.hasNext)>,<endif>
                }>
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    public Mono<Long> upsert(@NonNull List<SpanRow> rows, @NonNull String workspaceId, @NonNull String userName) {
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

        statement.bind("workspace_id", workspaceId);
        for (int i = 0; i < rows.size(); i++) {
            SpanRow row = rows.get(i);
            statement.bind("project_id" + i, row.projectId())
                    .bind("trace_id" + i, row.traceId())
                    .bind("span_id" + i, row.spanId())
                    .bind("start_time" + i, ClickHouseDateTimeFormat.formatNanos(row.startTime()))
                    .bind("model" + i, row.model())
                    .bind("u_input" + i, row.uInput())
                    .bind("u_cache_read" + i, row.uCacheRead())
                    .bind("u_cache_creation" + i, row.uCacheCreation())
                    .bind("u_output" + i, row.uOutput())
                    .bind("blocks_json" + i, row.blocksJson());
        }

        return statement.execute();
    }
}
