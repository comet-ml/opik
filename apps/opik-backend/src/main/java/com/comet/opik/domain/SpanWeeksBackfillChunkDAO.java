package com.comet.opik.domain;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;

/**
 * The span weeks backfill's progress: its planned chunks and which are done. Lookups by {@code from_week} use the
 * primary key; the pending and completion reads use {@code span_weeks_backfill_chunks_backfilled_at_idx}.
 */
@RegisterConstructorMapper(SpanWeeksBackfillChunk.class)
interface SpanWeeksBackfillChunkDAO {

    @SqlBatch("INSERT INTO span_weeks_backfill_chunks (from_week, to_week) VALUES (:fromWeek, :toWeek)")
    int[] insert(@BindMethods List<SpanWeeksBackfillChunk> chunks);

    @SqlQuery("SELECT COUNT(*) FROM span_weeks_backfill_chunks")
    long count();

    /** A plan exists and every one of its ranges is backfilled. */
    @SqlQuery("SELECT COUNT(*) > 0 AND COUNT(backfilled_at) = COUNT(*) FROM span_weeks_backfill_chunks")
    boolean isComplete();

    @SqlQuery("""
            SELECT from_week, to_week
            FROM span_weeks_backfill_chunks
            WHERE backfilled_at IS NULL
            ORDER BY from_week
            LIMIT 1
            """)
    Optional<SpanWeeksBackfillChunk> findNextPending();

    @SqlUpdate("UPDATE span_weeks_backfill_chunks SET backfilled_at = CURRENT_TIMESTAMP(6) WHERE from_week = :fromWeek")
    int markBackfilled(@Bind("fromWeek") long fromWeek);
}
