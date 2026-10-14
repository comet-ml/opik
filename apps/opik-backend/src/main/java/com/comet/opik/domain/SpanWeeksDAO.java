package com.comet.opik.domain;

import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.infrastructure.db.JsonEachRowBulkInsert;
import com.comet.opik.utils.JsonUtils;
import com.google.common.base.Preconditions;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.FilterUtils.getLogComment;
import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

/**
 * Writes and reads span_weeks, the index of which weekly partitions of the spans table each trace's spans occupy.
 * Inserts go through the ClickHouse v2 client as JSONEachRow, and still take the async-insert path; repeated
 * registrations of the same (trace, week) are collapsed by the ReplacingMergeTree.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SpanWeeksDAO {

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

    private static final String TABLE = "span_weeks";

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull JsonEachRowBulkInsert jsonBulkInsert;
    private final @NonNull InstantToUUIDMapper uuidMapper;

    public Mono<Long> insert(List<SpanWeek> rows) {
        if (CollectionUtils.isEmpty(rows)) {
            return Mono.just(0L);
        }
        return makeMonoContextAware((userName, workspaceId) -> jsonBulkInsert.insert(
                TABLE,
                getLogComment("insert_span_weeks", workspaceId, userName, "span_weeks_rows:%d".formatted(rows.size())),
                rows,
                row -> {
                    var node = JsonUtils.createObjectNode();
                    node.put("workspace_id", workspaceId);
                    node.put("project_id", row.projectId().toString());
                    node.put("trace_id", row.traceId().toString());
                    node.put("id_week", row.idWeek());
                    return node;
                }));
    }

    /**
     * The weeks the given traces' spans are registered in. Bounded by the trace ids, each holding a handful of weeks
     * (one per week its spans' ids fall in), so the result needs no pagination. Streamed; collecting is the caller's
     * choice.
     */
    public Flux<SpanWeek> findByTraceIds(Set<UUID> traceIds) {
        if (CollectionUtils.isEmpty(traceIds)) {
            return Flux.empty();
        }
        return makeFluxContextAware((userName, workspaceId) -> Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    ST template = getSTWithLogComment(FIND_BY_TRACE_IDS, "find_span_weeks_by_trace_ids", workspaceId,
                            userName, "trace_ids:%d".formatted(traceIds.size()));
                    return Flux.from(connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId)
                            .bind("trace_ids", traceIds.stream().map(UUID::toString).toArray(String[]::new))
                            .execute());
                })
                .flatMap(result -> result.map((row, metadata) -> SpanWeek.builder()
                        .projectId(row.get("project_id", UUID.class))
                        .traceId(row.get("trace_id", UUID.class))
                        .idWeek(row.get("id_week", Long.class))
                        .build())));
    }

    /** When the first span was written, if any; one scan of the created_at column. */
    public Mono<Optional<Instant>> findFirstCreatedAt(long maxExecutionSeconds) {
        Preconditions.checkArgument(maxExecutionSeconds > 0, "Argument 'maxExecutionSeconds' must be positive");
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(
                        getSTWithLogComment(FIND_FIRST_CREATED_AT, "find_spans_first_created_at", null, null,
                                "span_weeks_backfill_plan")
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
                            "from_week:%d,to_week:%s".formatted(fromWeek, toWeek))
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
