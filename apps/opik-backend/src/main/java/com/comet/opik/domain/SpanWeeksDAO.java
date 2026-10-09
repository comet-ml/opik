package com.comet.opik.domain;

import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.base.Preconditions;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

/**
 * Writes and reads span_weeks, the index of which weekly partitions of the spans table each trace's spans occupy.
 * Plain {@code FORMAT Values} inserts, so they take the async-insert path like every other listener DAO; repeated
 * registrations of the same (trace, week) are collapsed by the ReplacingMergeTree.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SpanWeeksDAO {

    private static final String INSERT = """
            INSERT INTO span_weeks (workspace_id, project_id, trace_id, id_week)
            SETTINGS log_comment = '<log_comment>'
            FORMAT Values
                <items:{item |
                    (:workspace_id, :project_id<item.index>, :trace_id<item.index>, :id_week<item.index>)
                    <if(item.hasNext)>,<endif>
                }>
            ;
            """;

    private static final String FIND_BY_TRACE_IDS = """
            SELECT DISTINCT project_id, trace_id, id_week
            FROM span_weeks
            WHERE workspace_id = :workspace_id
            AND trace_id IN :trace_ids
            SETTINGS log_comment = '<log_comment>'
            """;

    // The spans_local_v2 partition expression, derived from the id instead of id_at: the legacy spans table declares
    // id_at as a 32-bit DateTime, which wraps far-future ids, so its own id_at cannot give the same week.
    private static final String ID_WEEK = "toUInt32(toYYYYMMDD(toDate32(toDateTime64(UUIDv7ToDateTime(toUUID(id)), 0, 'UTC'))"
            + " - toIntervalDay(toDayOfWeek(toDateTime64(UUIDv7ToDateTime(toUUID(id)), 0, 'UTC'), 1))))";

    // created_at is always set by ClickHouse (now64), never by the client, so its minimum is the first span write.
    private static final String FIND_FIRST_CREATED_AT = """
            SELECT toUnixTimestamp64Milli(minOrNull(created_at)) AS first_created_at
            FROM spans
            SETTINGS log_comment = '<log_comment>', max_execution_time = <max_execution_time>
            """;

    // The id range only selects which spans a chunk reads, pruning granules through the idx_spans_id minmax index;
    // each span's week still comes from its id. Threads are capped so the backfill stays a small share of the server;
    // memory is left to the profile's max_bytes_ratio_before_external_group_by spill. In-order aggregation over the sort key measured ~200x
    // slower, hence off.
    private static final String BACKFILL = """
            INSERT INTO span_weeks (workspace_id, project_id, trace_id, id_week)
            SELECT workspace_id, project_id, trace_id, <id_week> AS week
            FROM spans
            WHERE true
            <if(from_id)>AND id >= :from_id<endif>
            <if(to_id)>AND id \\< :to_id<endif>
            GROUP BY workspace_id, project_id, trace_id, week
            SETTINGS log_comment = '<log_comment>', optimize_aggregation_in_order = 0,
                max_threads = <max_threads>, max_insert_threads = <max_threads>,
                max_execution_time = <max_execution_time>
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull InstantToUUIDMapper uuidMapper;

    public Mono<Long> insert(@NonNull List<SpanWeek> rows) {
        if (rows.isEmpty()) {
            return Mono.just(0L);
        }
        return makeMonoContextAware((userName, workspaceId) -> Mono.from(connectionFactory.create())
                .flatMapMany(connection -> insert(rows, workspaceId, userName, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum));
    }

    private Publisher<? extends Result> insert(List<SpanWeek> rows, String workspaceId, String userName,
            Connection connection) {
        List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(rows.size());
        ST template = getSTWithLogComment(INSERT, "insert_span_weeks", workspaceId, userName, rows.size());
        template.add("items", queryItems);
        Statement statement = connection.createStatement(template.render());

        // Positional binds, as in CipxTraceIdentityDAO: workspace_id once at 0, then three per row in template order.
        statement.bind(0, workspaceId);
        int index = 1;
        for (SpanWeek row : rows) {
            statement.bind(index++, row.projectId().toString())
                    .bind(index++, row.traceId().toString())
                    .bind(index++, row.idWeek());
        }
        return statement.execute();
    }

    public Mono<List<SpanWeek>> findByTraceIds(@NonNull Collection<UUID> traceIds) {
        Preconditions.checkArgument(!traceIds.isEmpty(), "Argument 'traceIds' must not be empty");
        return makeMonoContextAware((userName, workspaceId) -> Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    ST template = getSTWithLogComment(FIND_BY_TRACE_IDS, "find_span_weeks_by_trace_ids", workspaceId,
                            userName, traceIds.size());
                    return Flux.from(connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId)
                            .bind("trace_ids", traceIds.stream().map(UUID::toString).toArray(String[]::new))
                            .execute());
                })
                .flatMap(result -> result.map((row, metadata) -> SpanWeek.builder()
                        .projectId(row.get("project_id", UUID.class))
                        .traceId(row.get("trace_id", UUID.class))
                        .idWeek(row.get("id_week", Long.class))
                        .build()))
                .collectList());
    }

    /** When the first span was written, if any; one scan of the created_at column. */
    public Mono<Optional<Instant>> findFirstCreatedAt(long maxExecutionSeconds) {
        Preconditions.checkArgument(maxExecutionSeconds > 0, "Argument 'maxExecutionSeconds' must be positive");
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(
                        getSTWithLogComment(FIND_FIRST_CREATED_AT, "find_spans_first_created_at", null, null, null)
                                .add("max_execution_time", maxExecutionSeconds)
                                .render())
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> Optional
                        .ofNullable(row.get("first_created_at", Long.class))
                        .map(Instant::ofEpochMilli)))
                .next()
                .defaultIfEmpty(Optional.empty());
    }

    /**
     * Registers the week of every span whose id is from Monday {@code fromWeek}'s UUIDv7 up to Monday
     * {@code toWeek}'s; {@code fromWeek} 0 and a null {@code toWeek} leave that end open. A span's week comes from
     * its id, so one whose id sorts outside its own week (a non-v7 id) is still registered, in whichever range holds it.
     * The statement uses at most {@code maxThreads} threads.
     */
    public Mono<Void> backfill(long fromWeek, Long toWeek, int maxThreads, long maxExecutionSeconds) {
        Preconditions.checkArgument(fromWeek >= 0, "Argument 'fromWeek' must not be negative");
        Preconditions.checkArgument(toWeek == null || toWeek > fromWeek,
                "Argument 'toWeek' must be after 'fromWeek', or null for an open range");
        Preconditions.checkArgument(maxThreads > 0, "Argument 'maxThreads' must be positive");
        Preconditions.checkArgument(maxExecutionSeconds > 0, "Argument 'maxExecutionSeconds' must be positive");
        UUID fromId = fromWeek > 0 ? uuidMapper.toLowerBound(monday(fromWeek)) : null;
        UUID toId = toWeek != null ? uuidMapper.toLowerBound(monday(toWeek)) : null;
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    ST template = getSTWithLogComment(BACKFILL, "backfill_span_weeks", null, null,
                            fromWeek + "-" + toWeek)
                            .add("id_week", ID_WEEK)
                            .add("from_id", fromId != null)
                            .add("to_id", toId != null)
                            .add("max_threads", maxThreads)
                            .add("max_execution_time", maxExecutionSeconds);
                    Statement statement = connection.createStatement(template.render());
                    if (fromId != null) {
                        statement.bind("from_id", fromId.toString());
                    }
                    if (toId != null) {
                        statement.bind("to_id", toId.toString());
                    }
                    return statement.execute();
                })
                .flatMap(Result::getRowsUpdated)
                .then();
    }

    private static Instant monday(long week) {
        LocalDate date = LocalDate.parse(String.valueOf(week), DateTimeFormatter.BASIC_ISO_DATE);
        Preconditions.checkArgument(date.getDayOfWeek() == DayOfWeek.MONDAY,
                "Week '%s' must be a Monday as YYYYMMDD", week);
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
