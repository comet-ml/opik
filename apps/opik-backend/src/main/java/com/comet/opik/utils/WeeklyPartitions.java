package com.comet.opik.utils;

import lombok.NonNull;
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
 * merely close is a silently skipped delete, not a slower one. So this returns a set only when every id in the batch
 * is one it can derive exactly, and empty otherwise — leaving the caller to emit its unbounded form, which is always
 * correct and merely slower. The two rejections are:</p>
 * <ul>
 *     <li><b>Any id that is not a UUIDv7.</b> Its high 48 bits are not a timestamp, and {@code UUIDv7ToDateTime}
 *     returns {@code 1970-01-01} for it rather than throwing, so the row sits in the epoch partition while the bits
 *     read as an arbitrary week. All-or-nothing across the batch, not per id: a partially derived set is a set the
 *     rows of the underivable ids are not in.</li>
 *     <li><b>Any id whose embedded timestamp is outside {@code DateTime64}'s range</b> ({@link #ID_AT_CEILING}). Java
 *     has no such bound, so past it the two disagree: ClickHouse stores the saturated bound and the row lands in
 *     {@code 22991225}, while this would compute the real (out-of-range) week. Rejecting is deliberate in preference
 *     to clamping to {@code 22991225}: clamping would make correctness depend on reproducing ClickHouse's saturation
 *     semantics exactly — including where the saturation happens, which is already two steps before {@code toDate32}
 *     (see below) — to buy pruning for ids that should not exist. Falling back to the unbounded mutation costs
 *     performance on those batches and nothing else.</li>
 * </ul>
 *
 * <p>Far-future ids <em>within</em> the range are supported on purpose and are the common case of the two: a UUIDv7
 * minted with a bad clock (litellm <a href="https://github.com/BerriAI/litellm/issues/31294">BerriAI/litellm#31294</a>
 * mints ~2201) has a bogus but self-consistent {@code id_at}, so it lives in the far-future partition this computes and
 * stays deletable and prunable. Verified on prod-test: 0 partition mismatches across 11.23 M far-future rows.</p>
 */
@UtilityClass
public class WeeklyPartitions {

    /**
     * First instant {@code id_at} cannot represent. {@code DateTime64} spans
     * {@code [1900-01-01 00:00:00, 2299-12-31 23:59:59.99999999]} and saturates rather than wrapping or throwing, and
     * it saturates twice over before {@code toDate32} is reached: {@code UUIDv7ToDateTime} already returns
     * {@code DateTime64(3)}, and the column is {@code DateTime64(0)}. So an id at or past this instant is stored as
     * {@code 2299-12-31 23:59:59} and partitions as {@code 22991225} (that Sunday's Monday) whatever its real week —
     * observable on prod-test, whose far-future rows top out at exactly {@code 2299-12-31}.
     */
    private static final long ID_AT_CEILING = LocalDate.of(2300, 1, 1)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli();

    /**
     * The weekly partition values the ids resolve to, or empty if the batch contains an id whose partition cannot be
     * derived exactly (see the class javadoc) — in which case the caller must omit its partition predicate entirely.
     * An empty batch yields empty for the same reason: there is nothing to bound the mutation to.
     * <p>
     * A {@code null} batch throws rather than reading as empty, which is the one place this class is deliberately
     * intolerant. Empty is a <em>documented answer</em> — "this batch cannot be pruned, emit the unbounded form" — and a
     * caller that lost its batch would receive that answer, silently issue a correct-but-unbounded mutation, and never
     * learn it had a bug. A null collection here is a programming error, not a data condition; a null <em>element</em>
     * is the data condition, and that keeps returning empty.
     *
     * @throws NullPointerException if {@code ids} is null.
     */
    public static Optional<Set<Long>> of(@NonNull Collection<UUID> ids) {
        var partitions = new HashSet<Long>();

        for (UUID id : ids) {
            if (id == null || id.version() != 7) {
                return Optional.empty();
            }
            // UUIDv7: the high 48 bits are the unix epoch in milliseconds. `>>> 16` reads them unsigned, so the value
            // is in [0, 2^48) — never negative, and never large enough for Instant.ofEpochMilli to overflow. That is
            // also why only the ceiling is checked: the floor of the range is 1900-01-01, the smallest id_at any
            // UUIDv7 can carry is the epoch, and even its Monday (1969-12-29) is comfortably inside Date32.
            long epochMilli = id.getMostSignificantBits() >>> 16;
            if (epochMilli >= ID_AT_CEILING) {
                return Optional.empty();
            }
            LocalDate monday = Instant.ofEpochMilli(epochMilli)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            partitions.add(monday.getYear() * 10000L + monday.getMonthValue() * 100L + monday.getDayOfMonth());
        }

        // Set.copyOf, not the working HashSet: what escapes here decides which partitions a DELETE mutation touches, so
        // a caller holding a mutable reference could narrow the set after it was derived and turn a correct delete into
        // a silent no-op. Immutable by default per apps/opik-backend/AGENTS.md, and the accumulator stays local.
        return partitions.isEmpty() ? Optional.empty() : Optional.of(Set.copyOf(partitions));
    }
}
