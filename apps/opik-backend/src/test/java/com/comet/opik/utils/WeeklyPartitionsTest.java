package com.comet.opik.utils;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Covers {@link WeeklyPartitions#groupByPartition}, which groups a delete batch's ids by the weekly partition each
 * resolves to so the mutation can prune instead of rewriting every part (OPIK-8230), and
 * {@link WeeklyPartitions#weeksOf}, which returns the same partitions as one ascending list for a read that carries
 * them as a single {@code IN} bound (OPIK-8332). The two share {@code partitionsOf}, so the {@code weeksOf} cases pin
 * what differs — the flattening, the ordering, and that the all-or-nothing rule survives both — rather than
 * re-deriving every era. Their ids are therefore minted at random instants and their expectations built from the
 * derivation's single-id answer, which the era cases below pin: what they assert is the relationship between the two,
 * which holds whatever week the ids land in. The far-future case is the exception and says why.
 * <p>
 * The expected values are not hand-computed: each is what ClickHouse itself returned for
 * {@code toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))} for that id, evaluated once with
 * {@code id_at} as {@code DateTime64(0, 'UTC')} (the partitioned successor) and once as {@code DateTime('UTC')} (legacy
 * {@code traces}). If the table's partition expression, or ClickHouse's conversion into either column type, ever
 * changes, these assertions are what should fail.
 * <p>
 * The range-boundary cases are the exception and say so: the ids are constructed rather than observed, because the point
 * of each is an {@code id_at} value the column cannot store, which is exactly what no real row has.
 * <p>
 * <b>So a written-out id here means one of two things, never laziness.</b> It is either pinned to what ClickHouse
 * returned for it, or constructed to sit past a boundary. Where the id is incidental — the tests asserting grouping
 * shape, immutability or the all-or-nothing rule, none of which name a partition value — it is minted fresh from
 * {@link IdGenerator} instead.
 */
class WeeklyPartitionsTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    /**
     * The far-future era, the one case whose expected values cannot be generated: an id past 2106 resolves to two
     * weeks, and the second is what the legacy 32-bit column wraps it to. Naming them means naming this instant, and
     * both are the values the era cases pin against ClickHouse. Every other {@code weeksOf} case takes a random
     * {@code id_at}, because what those pin — flattening across ids, ascending order, the all-or-nothing rule — does
     * not depend on which week the ids land in.
     */
    private static final Instant FAR_FUTURE_ID_AT = Instant.parse("2200-01-01T00:00:00Z");

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("groupByPartition matches the partitions ClickHouse computed, under each id_at type")
    void matchesThePartitionsClickHouseComputed(String era, UUID id, Set<Long> expectedPartitions) {
        assertThat(WeeklyPartitions.groupByPartition(List.of(id)).map(Map::keySet)).contains(expectedPartitions);
    }

    /**
     * One row per era the derivation has to get right, each pinned to the value ClickHouse itself returned for that id —
     * not to a hand-computed Monday. The eras are not interchangeable samples: an expression that is correct for the
     * ordinary calendar and wrong at the extremes is exactly the {@code toMonday} trap migration 000114 was written to
     * escape, so dropping any of the three would weaken the pin rather than tidy it.
     */
    private static Stream<Arguments> matchesThePartitionsClickHouseComputed() {
        return Stream.of(
                // The ordinary case: id_at 2026-08-19 (a Wednesday) -> Monday 2026-08-17. Inside the 32-bit DateTime
                // range, so both column types store the same instant and the id contributes a SINGLE value. That is
                // every id real traffic produces, and it is why naming both representations costs nothing: the
                // partitions a normal delete resolves to are exactly the ones it resolved to before the legacy one
                // was added.
                arguments("ordinary id", UUID.fromString("01a01a75-76de-785e-ae84-8870ed5e6db3"), Set.of(20260817L)),
                // Long before Opik existed but well after the Unix epoch, and well inside Date32's 1900 floor: an id
                // this old prunes like any other, and likewise fits both column types. id_at 1996-02-09 -> 1996-02-05.
                arguments("id from 1996", UUID.fromString("00bfd451-fa93-7c10-9923-88a219a974c8"), Set.of(19960205L)),
                // Far-future ids are supported, not excluded, and are the only ones that contribute two values: past
                // 2106 the two column types disagree, so the batch has to name both weeks or be wrong on one schema -
                // which is what removed the cutover flag. DateTime64 stores 2200-01-01 -> Monday 2199-12-30; the legacy
                // 32-bit DateTime wraps the same id to 2063-11-25 -> Monday 2063-11-19. A grouping carrying only one of
                // the two is a delete that reports success and removes nothing on the other schema. 4.1% of rows on
                // prod-test look like this.
                arguments("far-future id", UUID.fromString("0699eb8a-59dd-7215-8000-03b8d2a8d5e2"),
                        Set.of(21991230L, 20631119L)));
    }

    @Test
    @DisplayName("a single non-v7 id yields no partitions")
    void singleNonV7() {
        assertThat(WeeklyPartitions.groupByPartition(List.of(UUID.fromString("9f527bac-527a-4f92-8875-0fa8af8e4f22"))))
                .isEmpty();
    }

    @Test
    @DisplayName("the last id_at the column can store still prunes")
    void lastRepresentableIdStillPrunes() {
        // id_at 2299-12-31T23:59:59.999 — the last instant DateTime64 represents, so nothing saturates: DateTime64(0)
        // truncates to 23:59:59, whose Date32 is 2299-12-31 (a Sunday) -> Monday 2299-12-25. The legacy DateTime wraps
        // the same id to 2027-10-18, a Monday. The ceiling check must be exclusive at exactly this point, hence a case
        // sitting on it.
        assertThat(WeeklyPartitions.groupByPartition(List.of(UUID.fromString("0978a65f-77ff-7abc-8000-000000000001")))
                .map(Map::keySet))
                .contains(Set.of(22991225L, 20271018L));
    }

    @Test
    @DisplayName("an id one millisecond past the id_at ceiling disables pruning")
    void firstUnrepresentableIdDisablesPruning() {
        // id_at 2300-01-01T00:00:00 — one ms past the previous case and outside DateTime64. ClickHouse saturates it to
        // 2299-12-31 23:59:59 and files the row under 22991225, so the honest week this would compute (2300-01-01 is
        // itself a Monday, giving 23000101) is a partition the row is NOT in. Pruning off rather than clamped: unlike
        // the legacy 32-bit wrap, which is a modulo this reproduces exactly, matching ClickHouse here would mean
        // reproducing its saturation semantics, for ids that should not exist.
        assertThat(WeeklyPartitions.groupByPartition(List.of(UUID.fromString("0978a65f-7800-7abc-8000-000000000001"))))
                .isEmpty();
    }

    @Test
    @DisplayName("the largest timestamp a UUIDv7 can carry disables pruning, and does not throw")
    void largestUuidV7TimestampDisablesPruning() {
        // All 48 timestamp bits set: 10889-08-02, the furthest future any UUIDv7 can encode. Read unsigned it is still
        // only ~2.8e14 ms, far inside Instant's range — so the guard is what excludes it, not an exception, and there
        // is no input on which this can throw.
        assertThat(WeeklyPartitions.groupByPartition(List.of(UUID.fromString("ffffffff-ffff-7abc-8000-000000000001"))))
                .isEmpty();
    }

    @Test
    @DisplayName("the earliest id a UUIDv7 can carry is inside Date32, so there is no floor to guard")
    void earliestUuidV7TimestampIsInRange() {
        // All 48 timestamp bits clear: id_at 1970-01-01, the earliest any UUIDv7 can encode (the field is unsigned).
        // Its Monday, 1969-12-29, is 70 years above Date32's 1900 floor, so a below-1900 id_at is unreachable by
        // construction rather than merely untested — which is why the derivation guards only the ceiling. It is also
        // the floor of the legacy 32-bit DateTime, so the wrap leaves it untouched and it contributes one value.
        assertThat(WeeklyPartitions.groupByPartition(List.of(UUID.fromString("00000000-0000-7abc-8000-000000000001")))
                .map(Map::keySet))
                .contains(Set.of(19691229L));
    }

    @Test
    @DisplayName("groupByPartition puts each ordinary id under its own single partition")
    void groupByPartitionOrdinaryBatch() {
        var ordinary = UUID.fromString("01a01a75-76de-785e-ae84-8870ed5e6db3"); // -> 20260817L
        var from1996 = UUID.fromString("00bfd451-fa93-7c10-9923-88a219a974c8"); // -> 19960205L

        assertThat(WeeklyPartitions.groupByPartition(List.of(ordinary, from1996)))
                .contains(Map.of(
                        20260817L, Set.of(ordinary),
                        19960205L, Set.of(from1996)));
    }

    @Test
    @DisplayName("groupByPartition puts a far-future id under both of its partitions")
    void groupByPartitionFarFutureIdAppearsTwice() {
        // Same id as matchesThePartitionsClickHouseComputed's far-future case: id_at 2200-01-01 partitions as
        // 21991230 on the successor and as the legacy 32-bit wrap's 20631119. of() unions these into one set; here
        // the id must appear as a MEMBER of both groups, since a statement scoped to 21991230 and one scoped to
        // 20631119 each need this id in their own bound (project_id, id) pairs to actually delete the row wherever
        // it physically lives.
        var farFuture = UUID.fromString("0699eb8a-59dd-7215-8000-03b8d2a8d5e2");

        assertThat(WeeklyPartitions.groupByPartition(List.of(farFuture)))
                .contains(Map.of(
                        21991230L, Set.of(farFuture),
                        20631119L, Set.of(farFuture)));
    }

    @Test
    @DisplayName("groupByPartition mixes a far-future id into an ordinary id's group when they share a partition")
    void groupByPartitionSharedPartitionMergesIntoOneGroup() {
        // Two DIFFERENT ordinary ids whose legacy 1996 id lands in the same week (19960205) as the far-future id's
        // legacy-wrapped value — this is the case that would break a naive "one group per id" implementation: the
        // far-future id's SECOND value must merge into an existing group, not create a fresh singleton group that
        // happens to collide.
        var from1996 = UUID.fromString("00bfd451-fa93-7c10-9923-88a219a974c8"); // -> 19960205L only
        var farFuture = UUID.fromString("0699eb8a-59dd-7215-8000-03b8d2a8d5e2"); // -> 21991230L, 20631119L

        var grouped = WeeklyPartitions.groupByPartition(List.of(from1996, farFuture)).orElseThrow();

        assertThat(grouped.keySet()).containsExactlyInAnyOrder(19960205L, 21991230L, 20631119L);
        assertThat(grouped.get(19960205L)).containsExactlyInAnyOrder(from1996);
        assertThat(grouped.get(21991230L)).containsExactlyInAnyOrder(farFuture);
        assertThat(grouped.get(20631119L)).containsExactlyInAnyOrder(farFuture);
    }

    @Test
    @DisplayName("groupByPartition collapses duplicate ids in the same partition into one entry via the Set")
    void groupByPartitionDuplicatesCollapseWithinAGroup() {
        var id = ID_GENERATOR.generateId();

        assertThat(WeeklyPartitions.groupByPartition(List.of(id, id)).orElseThrow())
                .hasSize(1)
                .allSatisfy((_, ids) -> assertThat(ids).containsExactly(id));
    }

    @Test
    @DisplayName("groupByPartition disables pruning for the whole batch on a non-v7 id, same as of()")
    void groupByPartitionNonV7DisablesPruning() {
        // randomUUID is a v4 by definition: no embedded timestamp to derive a partition from.
        assertThat(WeeklyPartitions.groupByPartition(List.of(ID_GENERATOR.generateId(), UUID.randomUUID())))
                .isEmpty();
    }

    @Test
    @DisplayName("groupByPartition disables pruning for the whole batch on an out-of-range id, same as of()")
    void groupByPartitionOutOfRangeIdDisablesPruning() {
        // The second is constructed, not observed: all 48 timestamp bits set, past anything Date32 can store.
        assertThat(WeeklyPartitions.groupByPartition(List.of(ID_GENERATOR.generateId(),
                UUID.fromString("ffffffff-ffff-7abc-8000-000000000001"))))
                .isEmpty();
    }

    @Test
    @DisplayName("groupByPartition on an empty batch yields no groups")
    void groupByPartitionEmptyBatch() {
        assertThat(WeeklyPartitions.groupByPartition(List.of())).isEmpty();
    }

    @Test
    @DisplayName("groupByPartition on a null batch throws rather than reading as unprunable")
    void groupByPartitionNullBatchThrows() {
        assertThatThrownBy(() -> WeeklyPartitions.groupByPartition(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ids");
    }

    @Test
    void weeksOfReturnsOneAscendingValuePerWeek() {
        // Exactly one week apart, so the two land in different weeks whatever the random base: a week is seven days,
        // so the later id's Monday is always the next one. Passed newest-first, so the ascending order in the result
        // is the derivation's and not the caller's.
        //
        // The expectation is built from the derivation's own single-id answer on purpose: what this pins is the
        // RELATION between the two calls - a batch's weeks are its ids' weeks, flattened, deduplicated and ascending
        // - and the per-era values it composes are pinned against ClickHouse above. Not self-confirming: dropping
        // .sorted(), .distinct() or the flattening from weeksOf each fails one of these cases, because a single-id
        // call is unaffected by all three.
        var olderIdAt = randomIdAt();
        var older = ID_GENERATOR.generateId(olderIdAt);
        var newer = ID_GENERATOR.generateId(olderIdAt.plus(7, ChronoUnit.DAYS));

        var actualWeeks = WeeklyPartitions.weeksOf(List.of(newer, older));
        var expectedWeeks = List.of(weekOf(older), weekOf(newer));

        assertThat(actualWeeks).hasValue(expectedWeeks);
    }

    @Test
    void weeksOfNamesBothWeeksOfAFarFutureId() {
        // A read bounds itself with ONE IN list inside one statement, so unlike groupByPartition there is no grouping
        // to put the id in twice - both of its weeks simply sit in the same list. Omitting either would make the
        // rendered query correct on one schema and a predicate matching nothing on the other.
        var farFuture = ID_GENERATOR.generateId(FAR_FUTURE_ID_AT);
        var expectedWeeks = List.of(20631119L, 21991230L);

        var actualWeeks = WeeklyPartitions.weeksOf(List.of(farFuture));

        assertThat(actualWeeks).hasValue(expectedWeeks);
    }

    @Test
    void weeksOfNamesAWeekOnceHoweverManyIdsFallInIt() {
        // The list is the set of weeks, not one entry per id: a page of same-week ids must bound its read with a
        // single value, or the IN list grows with the batch for no pruning gain. Both ids are minted at one instant,
        // which is the only way to be sure they share a week without the test computing weeks itself.
        var sharedIdAt = randomIdAt();
        var first = ID_GENERATOR.generateId(sharedIdAt);
        var second = ID_GENERATOR.generateId(sharedIdAt);

        var actualWeeks = WeeklyPartitions.weeksOf(List.of(first, second));
        var expectedWeeks = List.of(weekOf(first));

        assertThat(actualWeeks).hasValue(expectedWeeks);
    }

    private static Stream<Arguments> weeksOfYieldsNoWeeks() {
        return Stream.of(
                // Past the ceiling id_at saturates, so the week the row is filed under is not the week its id
                // computes. Real data contains such ids, so this arm is reached rather than theoretical.
                arguments("an id past the id_at ceiling", List.of(ID_GENERATOR.generateId(), ID_GENERATOR
                        .getTimeOrderedEpoch(LocalDate.of(2300, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
                                .toEpochMilli()))),
                // randomUUID is a v4 by definition: no embedded timestamp to derive a week from. Unreachable through
                // ingestion, which rejects a non-v7 id unconditionally, but the derivation still has to refuse it.
                arguments("a non-v7 id", List.of(ID_GENERATOR.generateId(), UUID.randomUUID())),
                // Nothing to bound anything to. Every caller guards this before reaching the derivation, so it is the
                // contract that is pinned here rather than a reachable path.
                arguments("an empty batch", List.<UUID>of()));
    }

    /**
     * The all-or-nothing rule, for the reason a read needs it: a partially derived set is a set some rows are not in,
     * and for a {@code SELECT} that is silently fewer rows rather than a slower query. One case per way the batch can
     * fail to derive, since they are one claim with one assertion.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void weeksOfYieldsNoWeeks(String reason, List<UUID> ids) {
        var actualWeeks = WeeklyPartitions.weeksOf(ids);

        assertThat(actualWeeks).isEmpty();
    }

    @Test
    void weeksOfOnANullBatchThrowsRatherThanReadingAsUnbounded() {
        assertThatThrownBy(() -> WeeklyPartitions.weeksOf(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("ids is marked non-null but is null");
    }

    @Test
    void weeksOfResultIsImmutable() {
        // What escapes here decides which partitions a read is bounded to, so a caller holding a mutable reference
        // could narrow it after derivation and turn a correct read into one that silently returns fewer rows.
        var actualWeeks = WeeklyPartitions.weeksOf(List.of(ID_GENERATOR.generateId())).orElseThrow();

        assertThatThrownBy(actualWeeks::removeFirst).isExactlyInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("groupByPartition's map and its per-partition sets are both immutable")
    void groupByPartitionResultIsImmutable() {
        // The map AND its values are load-bearing here: a caller that narrowed either would emit a DELETE ... IN
        // PARTITION statement missing one of its own bound ids — a correct-looking statement that deletes less than
        // it should, silently.
        var ordinary = ID_GENERATOR.generateId();
        var grouped = WeeklyPartitions.groupByPartition(List.of(ordinary)).orElseThrow();
        var partition = grouped.keySet().iterator().next();

        assertThatThrownBy(() -> grouped.remove(partition))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> grouped.get(partition).remove(ordinary))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * An {@code id_at} inside both column types, so an id minted at it resolves to a single week. Random, because no
     * case that uses it names a week: each builds its expectation from {@link #weekOf}.
     */
    private Instant randomIdAt() {
        return Instant.now().minus(ThreadLocalRandom.current().nextInt(1, 3_650), ChronoUnit.DAYS);
    }

    /**
     * The derivation's own answer for one id, which the per-era cases above pin against ClickHouse. The multi-id
     * cases build their expectations from it so they can use random ids: what they assert is the relationship
     * between the two — that a batch's weeks are its ids' weeks, deduplicated and ascending — which a broken
     * flatten, sort or dedup breaks regardless of the values.
     */
    private long weekOf(UUID id) {
        return WeeklyPartitions.weeksOf(List.of(id)).orElseThrow().getFirst();
    }
}
