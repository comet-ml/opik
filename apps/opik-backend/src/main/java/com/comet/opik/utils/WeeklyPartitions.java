package com.comet.opik.utils;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Single derivation point for the {@code id_at} weekly partition value(s) a batch of ids resolves to, so a mutation
 * can name its own partitions instead of being planned against every part of the table. {@link #groupByPartition}
 * groups ids by the partition each belongs to, for a caller emitting one statement per partition (OPIK-8230) that
 * must bind only the ids belonging to it.
 *
 * <p>Mirrors the partition expression of {@code traces_local_v2} / {@code spans_local_v2} exactly —
 * {@code PARTITION BY toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))}, where {@code id_at} is
 * {@code MATERIALIZED UUIDv7ToDateTime(toUUID(id))} — i.e. the Monday of the id's UTC week as {@code yyyyMMdd}. Both
 * tables share the expression, so it is derived here once rather than per DAO.</p>
 *
 * <h2>Why an id contributes two values, and why that removes a cutover flag</h2>
 *
 * <p>The predicate has to be correct against <b>both</b> shapes of the table a mutation may run into during the
 * {@code traces_local_v2} cutover, because nothing in the application marks the EXCHANGE that swaps them. They declare
 * {@code id_at} with different widths:</p>
 * <ul>
 *     <li><b>legacy {@code traces}</b> (migration {@code 000091}): {@code DateTime('UTC')} — 32-bit, so a timestamp
 *     past {@code 2106-02-07 06:28:15} is stored <b>wrapped</b> modulo 2<sup>32</sup> seconds;</li>
 *     <li><b>{@code traces_local_v2}</b> (migration {@code 000114}): {@code DateTime64(0, 'UTC')} — the honest value.</li>
 * </ul>
 *
 * <p>So a far-future id (a UUIDv7 minted with a bad clock — litellm
 * <a href="https://github.com/BerriAI/litellm/issues/31294">BerriAI/litellm#31294</a> mints ~2201, and prod already
 * holds such rows) partitions as {@code 22010803} on the successor and as the wrapped {@code 20650622} on the legacy
 * table. A set carrying only one of the two is correct on one schema and, on the other, a predicate that matches
 * nothing — a delete that reports success and removes no rows.</p>
 *
 * <p>Rather than being told which schema is live, each id therefore contributes the partition it resolves to under
 * <b>each</b> {@code id_at} type the mutation may meet. For every id before 2106 the two coincide and the set is
 * unchanged, which is all but a rounding error of real traffic; only a far-future id adds a second value. Widening an
 * {@code IN} set can only ever select an extra partition — the {@code (project_id, id)} predicate it is ANDed with
 * still decides which rows are deleted — so the union is safe in the direction that matters, while omitting a value is
 * not. Measured against a 220-partition table, the pruning is identical either way: 3/220 parts with the honest set,
 * 3/220 with the union.</p>
 *
 * <p>This is what lets the predicate be emitted unconditionally, with no flag asserting that the EXCHANGE has already
 * happened — matching the rest of the project's partition-pruning predicates, which are likewise written to hold
 * before and after the cutover.</p>
 *
 * <h2>Why the caller must treat an empty result as "no predicate", never as "no partitions"</h2>
 *
 * <p>The whole value of the derivation is that the partitions a batch resolves to are the <em>only</em> places its rows
 * can be; a grouping that is merely close is a silently skipped delete, not a slower one. So this returns non-empty
 * only when every id in the batch is one it can derive exactly, and empty otherwise — leaving the caller to emit its
 * unbounded form, which is always correct and merely slower. The two rejections are:</p>
 * <ul>
 *     <li><b>Any id that is not a UUIDv7.</b> Its high 48 bits are not a timestamp, and {@code UUIDv7ToDateTime}
 *     returns {@code 1970-01-01} for it rather than throwing, so the row sits in the epoch partition while the bits
 *     read as an arbitrary week. All-or-nothing across the batch, not per id: a partially derived set is a set the
 *     rows of the underivable ids are not in.</li>
 *     <li><b>Any id whose embedded timestamp is at or past {@link #ID_AT_CEILING}</b>, the end of {@code DateTime64}'s
 *     range. Past it the successor no longer stores a value this can reproduce: {@code DateTime64} saturates rather
 *     than wrapping, so the row lands in {@code 22991225} whatever its real week. Rejecting is deliberate in
 *     preference to adding {@code 22991225} to the union: that would make correctness depend on reproducing
 *     ClickHouse's saturation semantics exactly — including where the saturation happens, which is already two steps
 *     before {@code toDate32} — to buy pruning for ids no clock, however broken, produces. Falling back to the
 *     unbounded mutation costs performance on those batches and nothing else.</li>
 * </ul>
 */
@UtilityClass
public class WeeklyPartitions {

    /**
     * First instant {@code id_at} cannot represent on the partitioned successor. {@code DateTime64} spans
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
     * The wrap of legacy {@code traces.id_at}, a 32-bit {@code DateTime}: it holds
     * {@code epochSecond % 2^32}, so anything past {@code 2106-02-07 06:28:15} reappears somewhere in
     * {@code [1970-01-01, 2106-02-07]}. Also the threshold itself — below it the modulo is the identity, which is why
     * only an id past this point contributes a second value. Modulo, not saturation — verified against ClickHouse 26.3 across a spread of
     * ordinary and far-future ids, every row satisfying
     * {@code toUnixTimestamp(id_at) = intDiv(unix_ts_ms, 1000) % 4294967296}, and pinned by
     * {@code TracesLegacyTablePruningMutationTest}, which deletes a far-future row from the real legacy table — so a
     * change in that conversion fails CI rather than production.
     */
    private static final long ID_AT_LEGACY_MODULUS = 1L << 32;

    /**
     * Groups the ids by the weekly partition value(s) each resolves to — under each {@code id_at} type the mutation
     * may run against (see the class javadoc) — or empty if the batch contains an id whose partition cannot be
     * derived exactly. In the empty case the caller must omit its partition predicate entirely and emit the
     * unbounded form. An empty batch yields empty for the same reason: there is nothing to bound the mutation to.
     * {@code IN PARTITION} accepts exactly one partition per statement (OPIK-8230), so a caller emitting one
     * statement per partition needs to know which ids go in each, not merely their union.
     * <p>
     * An id past {@link #ID_AT_LEGACY_MODULUS} appears in <b>two</b> groups — one per {@code id_at} representation
     * it resolves to. That is safe, not a widening bug: a statement scoped to the group that does not actually
     * contain the id's row is a no-op there, never a wrong deletion, since the row-matching {@code (project_id, id)}
     * predicate inside each statement is unchanged regardless of which partition the statement targets.
     * <p>
     * A {@code null} batch throws rather than reading as empty: empty is a documented answer meaning "emit the
     * unbounded form", so a caller that lost its batch would silently get back the slow mutation this class exists to
     * avoid. A null <em>element</em> still returns empty, which is safe because it is unreachable from the delete
     * path: {@code TraceDAO#delete} rejects a null id before any of this runs.
     *
     * @throws NullPointerException if {@code ids} is null.
     */
    public static Optional<Map<Long, Set<UUID>>> groupByPartition(@NonNull Collection<UUID> ids) {
        var grouped = new HashMap<Long, Set<UUID>>();

        for (UUID id : ids) {
            var idPartitions = partitionsOf(id);
            if (idPartitions.isEmpty()) {
                return Optional.empty();
            }
            for (Long partition : idPartitions.get()) {
                grouped.computeIfAbsent(partition, _ -> new HashSet<>()).add(id);
            }
        }

        if (grouped.isEmpty()) {
            return Optional.empty();
        }
        // Immutable outer map AND immutable per-partition sets: what escapes here decides which partitions a DELETE
        // mutation touches, so a caller holding a mutable reference could narrow it after derivation and turn a
        // correct delete into a silent no-op. Collectors.toUnmodifiableMap's values must themselves be made
        // unmodifiable explicitly; it does not do so for you.
        return Optional.of(grouped.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue()))));
    }

    /**
     * The weekly partition value(s) a single id resolves to under each {@code id_at} type the mutation may meet (see
     * class javadoc) — extracted from {@link #groupByPartition} so the per-id {@code id_at} math reads separately
     * from the grouping it feeds. Empty exactly when the id cannot be derived exactly: not a UUIDv7, or an embedded
     * timestamp at or past {@link #ID_AT_CEILING}.
     */
    private static Optional<Set<Long>> partitionsOf(UUID id) {
        if (id == null || id.version() != 7) {
            return Optional.empty();
        }
        // UUIDv7: the high 48 bits are the unix epoch in milliseconds. `>>> 16` reads them unsigned, so the value
        // is in [0, 2^48) — never negative, and never large enough for Instant.ofEpochSecond to overflow. That is
        // also why neither derivation checks a floor: the smallest id_at any UUIDv7 can carry is the epoch, and
        // even its Monday (1969-12-29) is comfortably inside Date32 on both schemas.
        long epochMilli = id.getMostSignificantBits() >>> 16;
        if (epochMilli >= ID_AT_CEILING) {
            return Optional.empty();
        }
        // Truncating to whole seconds is the column's own conversion (DateTime64(0) / DateTime both store seconds)
        // and cannot move a value into an earlier day, so it never changes the week.
        long epochSecond = epochMilli / 1_000L;
        // DateTime64(0, 'UTC') — the partitioned successor. Only past the 32-bit range do the two columns disagree;
        // below it the modulo is the identity, so the branch is the invariant stated as code rather than an
        // optimisation: an ordinary id CANNOT widen the set. Immutable on the way out, for the same reason
        // groupByPartition copies its own result — see there. Set.of throws on a duplicate, which is safe here
        // rather than merely untested: the two arguments are weeks 2^32 seconds (~136 years) apart, so they cannot
        // be the same week.
        return Optional.of(epochSecond >= ID_AT_LEGACY_MODULUS
                ? Set.of(weeklyPartitionOf(epochSecond),
                        weeklyPartitionOf(epochSecond % ID_AT_LEGACY_MODULUS)) // DateTime('UTC') — legacy
                : Set.of(weeklyPartitionOf(epochSecond)));
    }

    /**
     * {@code toYYYYMMDD(toDate32(t) - toIntervalDay(toDayOfWeek(t, 1)))} for a UTC epoch second — the Monday of its
     * week as {@code yyyyMMdd}.
     */
    private static long weeklyPartitionOf(long epochSecond) {
        LocalDate monday = Instant.ofEpochSecond(epochSecond)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.getYear() * 10000L + monday.getMonthValue() * 100L + monday.getDayOfMonth();
    }
}
