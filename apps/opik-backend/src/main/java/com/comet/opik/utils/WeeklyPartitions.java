package com.comet.opik.utils;

import lombok.experimental.UtilityClass;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Single derivation point for the {@code id_at} weekly partition values a batch of ids resolves to, so a mutation can
 * name its own partitions instead of being planned against every part of the table.
 *
 * <p>Mirrors the partition expression of {@code traces_local_v2} / {@code spans_local_v2} exactly —
 * {@code PARTITION BY toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))}, where {@code id_at} is
 * {@code DateTime64(0, 'UTC') MATERIALIZED UUIDv7ToDateTime(toUUID(id))} — i.e. the Monday of the id's UTC week as
 * {@code yyyyMMdd}. Both tables share the expression, so it is derived here once rather than per DAO.</p>
 *
 * <p><b>Why the caller must treat an empty result as "no predicate", never as "no partitions".</b> The whole value of
 * the derivation is that a partition the batch resolves to is the <em>only</em> place its rows can be; a value that is
 * merely close is a silently skipped delete, not a slower one. So this returns a set only when every id in the batch is
 * a UUIDv7, and empty otherwise — leaving the caller to emit its unbounded form, which is always correct and merely
 * slower. Deriving a partition from a non-v7 id would read whatever bits sit in the timestamp field, and
 * {@code UUIDv7ToDateTime} returns {@code 1970-01-01} for it rather than throwing, so the row sits in the epoch
 * partition while the bits read as an arbitrary week. All-or-nothing across the batch, not per id: a partially derived
 * set is a set the rows of the underivable ids are not in.</p>
 *
 * <p>Far-future ids are supported on purpose: a UUIDv7 minted with a bad clock (litellm
 * <a href="https://github.com/BerriAI/litellm/issues/31294">BerriAI/litellm#31294</a> mints ~2201) has a bogus but
 * self-consistent {@code id_at}, so it lives in the far-future partition this computes and stays deletable and prunable.
 * Verified on prod-test: 0 partition mismatches across 11.23 M far-future rows.</p>
 */
@UtilityClass
public class WeeklyPartitions {

    /**
     * The weekly partition values the ids resolve to, or empty if the batch contains an id whose partition cannot be
     * derived exactly (see the class javadoc) — in which case the caller must omit its partition predicate entirely.
     * An empty batch yields empty for the same reason: there is nothing to bound the mutation to.
     */
    public static Optional<Set<Long>> of(Collection<UUID> ids) {
        var partitions = new HashSet<Long>();

        for (UUID id : ids) {
            if (id == null || id.version() != 7) {
                return Optional.empty();
            }
            // UUIDv7: the high 48 bits are the unix epoch in milliseconds.
            long epochMilli = id.getMostSignificantBits() >>> 16;
            LocalDate monday = Instant.ofEpochMilli(epochMilli)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            partitions.add(monday.getYear() * 10000L + monday.getMonthValue() * 100L + monday.getDayOfMonth());
        }

        return partitions.isEmpty() ? Optional.empty() : Optional.of(partitions);
    }
}
