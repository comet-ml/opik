package com.comet.opik.domain;

import lombok.Builder;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;

@RegisterConstructorMapper(SpanWeeksBackfillChunkDAO.Chunk.class)
interface SpanWeeksBackfillChunkDAO {

    /** The span ids from Monday fromWeek's UUIDv7 up to Monday toWeek's; 0 and null leave that end open. */
    @Builder(toBuilder = true)
    record Chunk(long fromWeek, Long toWeek) {
    }

    @SqlBatch("INSERT INTO span_weeks_backfill_chunks (from_week, to_week) VALUES (:fromWeek, :toWeek)")
    void insert(@BindMethods List<Chunk> chunks);

    @SqlQuery("SELECT COUNT(*) FROM span_weeks_backfill_chunks")
    long count();

    @SqlQuery("SELECT from_week, to_week FROM span_weeks_backfill_chunks"
            + " WHERE backfilled_at IS NULL ORDER BY from_week LIMIT 1")
    Optional<Chunk> findNextPending();

    @SqlUpdate("UPDATE span_weeks_backfill_chunks SET backfilled_at = CURRENT_TIMESTAMP(6) WHERE from_week = :fromWeek")
    void markBackfilled(@Bind("fromWeek") long fromWeek);
}
