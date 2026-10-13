package com.comet.opik.domain;

import jakarta.annotation.Nullable;
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

    /**
     * The span ids from Monday {@code fromWeek}'s UUIDv7 up to, not including, Monday {@code toWeek}'s; both are
     * YYYYMMDD. {@code fromWeek} 0 leaves the range open below, and a null {@code toWeek} leaves it open above.
     */
    @Builder(toBuilder = true)
    record Chunk(long fromWeek, @Nullable Long toWeek) {
    }

    @SqlBatch("INSERT INTO span_weeks_backfill_chunks (from_week, to_week) VALUES (:fromWeek, :toWeek)")
    void insert(@BindMethods List<Chunk> chunks);

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
    Optional<Chunk> findNextPending();

    @SqlUpdate("UPDATE span_weeks_backfill_chunks SET backfilled_at = CURRENT_TIMESTAMP(6) WHERE from_week = :fromWeek")
    void markBackfilled(@Bind("fromWeek") long fromWeek);
}
