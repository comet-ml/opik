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
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
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

    private static final String FIND_WEEKS = """
            SELECT toInt64(<id_week>) AS week, toInt64(count()) AS span_count
            FROM spans
            GROUP BY week
            ORDER BY week
            SETTINGS log_comment = '<log_comment>', max_execution_time = <max_execution_time>
            """;

    // The id range only prunes granules, through the idx_spans_id minmax index; the week filter decides membership.
    // GROUP BY rather than DISTINCT so a large chunk spills to disk, by the server's
    // max_bytes_ratio_before_external_group_by; in-order aggregation over the spans sort key measured ~200x slower.
    private static final String BACKFILL = """
            INSERT INTO span_weeks (workspace_id, project_id, trace_id, id_week)
            SELECT workspace_id, project_id, trace_id, <id_week> AS week
            FROM spans
            WHERE week BETWEEN :from_week AND :to_week
            <if(from_id)>AND id >= :from_id<endif>
            <if(to_id)>AND id \\< :to_id<endif>
            GROUP BY workspace_id, project_id, trace_id, week
            SETTINGS log_comment = '<log_comment>', optimize_aggregation_in_order = 0,
                max_execution_time = <max_execution_time>
            """;

    /** A spans week, the one spans_local_v2 stores a span in, and how many spans fall in it. */
    @Builder(toBuilder = true)
    public record WeekSpans(long week, long spanCount) {
    }

    // Non-v7 ids, whatever their leading bits, fall in this week, so a range holding it cannot bound its ids.
    private static final long EPOCH_WEEK = 19691229L;
    // Ids at or past it saturate into the last week, so a range reaching it cannot bound its ids from above.
    private static final Instant ID_CEILING = Instant.parse("2300-01-01T00:00:00Z");

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

    /** The weeks the spans hold, ascending, in one scan of the id column. */
    public Mono<List<WeekSpans>> findWeeks(long maxExecutionSeconds) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(
                        getSTWithLogComment(FIND_WEEKS, "find_spans_weeks", null, null, null)
                                .add("id_week", ID_WEEK)
                                .add("max_execution_time", maxExecutionSeconds)
                                .render())
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> WeekSpans.builder()
                        .week(row.get("week", Long.class))
                        .spanCount(row.get("span_count", Long.class))
                        .build()))
                .collectList();
    }

    /**
     * Registers the weeks of every span in {@code [fromWeek, toWeek]}. Unless the range holds the epoch week, its ids
     * are bounded by the UUIDv7s of its first Monday and of the Monday after it (below 2300), so the scan skips the
     * granules outside it.
     */
    public Mono<Void> backfill(long fromWeek, long toWeek, long maxExecutionSeconds) {
        boolean bounded = fromWeek > EPOCH_WEEK;
        Instant end = monday(toWeek).plus(7, ChronoUnit.DAYS);
        UUID fromId = bounded ? uuidMapper.toLowerBound(monday(fromWeek)) : null;
        UUID toId = bounded && end.isBefore(ID_CEILING) ? uuidMapper.toLowerBound(end) : null;
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    ST template = getSTWithLogComment(BACKFILL, "backfill_span_weeks", null, null,
                            fromWeek + "-" + toWeek)
                            .add("id_week", ID_WEEK)
                            .add("from_id", fromId != null)
                            .add("to_id", toId != null)
                            .add("max_execution_time", maxExecutionSeconds);
                    Statement statement = connection.createStatement(template.render())
                            .bind("from_week", fromWeek)
                            .bind("to_week", toWeek);
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
        return LocalDate.parse(String.valueOf(week), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC)
                .toInstant();
    }
}
