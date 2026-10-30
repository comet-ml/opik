package com.comet.opik.domain;

import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.infrastructure.SpanWeeksBackfillConfig;
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
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;

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

    /**
     * Registers the week of every span in one chunk's id range. The range only selects which spans are read, pruning
     * granules through the {@code idx_spans_id} minmax index; each span's week comes from its id, with the
     * {@code spans_local_v2} partition expression rather than {@code id_at}, which the legacy {@code spans} declares as
     * a 32-bit {@code DateTime} that wraps far-future ids.
     * <p>
     * Threads are capped so the backfill stays a small share of the server; memory is left to the profile's
     * {@code max_bytes_ratio_before_external_group_by} spill. {@code optimize_aggregation_in_order = 0} is the server
     * default, kept explicit because in-order aggregation over the sort key measured ~200x slower for this
     * {@code GROUP BY}, so a profile that enabled it would stall the backfill.
     */
    private static final String BACKFILL = """
            INSERT INTO span_weeks (workspace_id, project_id, trace_id, id_week)
            SELECT workspace_id, project_id, trace_id,
                toUInt32(toYYYYMMDD(toDate32(toDateTime64(UUIDv7ToDateTime(toUUID(id)), 0, 'UTC'))
                    - toIntervalDay(toDayOfWeek(toDateTime64(UUIDv7ToDateTime(toUUID(id)), 0, 'UTC'), 1)))) AS week
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
    private final @NonNull @Config("spanWeeksBackfill") SpanWeeksBackfillConfig backfillConfig;

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

    /**
     * Registers the week of every span in the chunk's id range (see {@link SpanWeeksBackfillChunk}). A span's week
     * comes from its id, so one whose id sorts outside its own week (a non-v7 id) is still registered, in whichever
     * range holds it. Threads and execution time come from {@code spanWeeksBackfill}.
     *
     * @return the rows the insert wrote
     */
    public Mono<Long> backfill(@NonNull SpanWeeksBackfillChunk chunk) {
        long fromWeek = chunk.fromWeek();
        Long toWeek = chunk.toWeek();
        Preconditions.checkArgument(fromWeek >= 0, "Argument 'fromWeek' must not be negative");
        Preconditions.checkArgument(toWeek == null || toWeek > fromWeek,
                "Argument 'toWeek' must be after 'fromWeek', or null for an open range");
        UUID fromId = fromWeek > 0 ? uuidMapper.toLowerBound(monday(fromWeek)) : null;
        UUID toId = toWeek != null ? uuidMapper.toLowerBound(monday(toWeek)) : null;
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    ST template = getSTWithLogComment(BACKFILL, "backfill_span_weeks", null, null,
                            "from_week:%d,to_week:%s".formatted(fromWeek, toWeek))
                            .add("from_id", fromId != null)
                            .add("to_id", toId != null)
                            .add("max_threads", backfillConfig.maxThreads())
                            .add("max_execution_time", backfillConfig.queryTimeout().toSeconds());
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
                .reduce(0L, Long::sum);
    }

    private static Instant monday(long week) {
        LocalDate date = LocalDate.parse(String.valueOf(week), DateTimeFormatter.BASIC_ISO_DATE);
        Preconditions.checkArgument(date.getDayOfWeek() == DayOfWeek.MONDAY,
                "Week '%s' must be a Monday as YYYYMMDD", week);
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
