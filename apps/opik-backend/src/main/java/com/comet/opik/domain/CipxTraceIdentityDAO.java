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
 * Writes the cipx_trace_identities table from cipx traces. Triggered asynchronously off trace
 * create/update events (identity can arrive or change on a trace update); never reads the traces or
 * cipx_trace_identities tables. Identity fields are parsed from metadata in Java
 * ({@link TraceIdentityRow#from}). Plain INSERT relying on ReplacingMergeTree to merge by sorting
 * key; last_updated_at is left to the column DEFAULT now64(6). project_id must be non-empty, so
 * blank rows are dropped.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class CipxTraceIdentityDAO {

    /** A cipx_trace_identities row constructed from a trace's metadata. */
    @Builder(toBuilder = true)
    public record TraceIdentityRow(
            @NonNull String traceId,
            @NonNull String projectId,
            @NonNull Instant startTime,
            @NonNull String userUuid,
            @NonNull String userEmail,
            @NonNull String userDisplayName,
            @NonNull String repository,
            @NonNull String sessionId,
            int schemaVersion) {

        public static TraceIdentityRow from(UUID traceId, UUID projectId, JsonNode metadata, Instant startTime) {
            JsonNode session = metadata.path("cipx").path("session");
            JsonNode identity = session.path("identity");
            return TraceIdentityRow.builder()
                    .traceId(traceId.toString())
                    .projectId(projectId != null ? projectId.toString() : "")
                    .startTime(startTime)
                    .userUuid(identity.path("user_uuid").asText(""))
                    .userEmail(identity.path("email").asText(""))
                    .userDisplayName(identity.path("display_name").asText(""))
                    .repository(session.path("repository").path("remote").asText(""))
                    .sessionId(session.path("session_id").asText(""))
                    .schemaVersion(session.path("schema_version").asInt(0))
                    .build();
        }
    }

    // One tuple per row (mirrors SpanDAO.BULK_INSERT). start_time is bound from Java (the source
    // trace's stored start).
    private static final String INSERT = """
            INSERT INTO cipx_trace_identities
                (workspace_id, project_id, trace_id, start_time, user_uuid,
                 user_email, user_display_name, repository, session_id, schema_version)
            SETTINGS log_comment = '<log_comment>'
            FORMAT Values
                <items:{item |
                    (
                        :workspace_id,
                        :project_id<item.index>,
                        :trace_id<item.index>,
                        :start_time<item.index>,
                        :user_uuid<item.index>,
                        :user_email<item.index>,
                        :user_display_name<item.index>,
                        :repository<item.index>,
                        :session_id<item.index>,
                        :schema_version<item.index>
                    )
                    <if(item.hasNext)>,<endif>
                }>
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    public Mono<Long> upsert(@NonNull List<TraceIdentityRow> rows, @NonNull String workspaceId,
            @NonNull String userName) {
        if (rows.isEmpty()) {
            return Mono.just(0L);
        }
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> insert(rows, workspaceId, userName, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    private Publisher<? extends Result> insert(List<TraceIdentityRow> rows, String workspaceId, String userName,
            Connection connection) {
        List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(rows.size());
        ST template = getSTWithLogComment(INSERT, "insert_cipx_trace_identities", workspaceId, userName, rows.size());
        template.add("items", queryItems);
        Statement statement = connection.createStatement(template.render());

        // Positional binds: the driver resolves named binds with a linear indexOf over the statement's
        // parameter list (quadratic per statement), while bind(int) is a direct array write. Indices
        // follow the placeholders' first-appearance order in the rendered SQL: workspace_id once at 0
        // (repeats dedup), then 9 parameters per row tuple in template order.
        statement.bind(0, workspaceId);
        int index = 1;
        for (TraceIdentityRow row : rows) {
            statement.bind(index++, row.projectId())
                    .bind(index++, row.traceId())
                    .bind(index++, ClickHouseDateTimeFormat.formatNanos(row.startTime()))
                    .bind(index++, row.userUuid())
                    .bind(index++, row.userEmail())
                    .bind(index++, row.userDisplayName())
                    .bind(index++, row.repository())
                    .bind(index++, row.sessionId())
                    .bind(index++, row.schemaVersion());
        }

        return statement.execute();
    }
}
