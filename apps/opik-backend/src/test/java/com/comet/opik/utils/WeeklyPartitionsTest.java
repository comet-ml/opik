package com.comet.opik.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Covers {@link WeeklyPartitions#of}, which derives the weekly partition values a delete batch resolves to so the
 * mutation can prune instead of rewriting every part.
 * <p>
 * The in-range expected values are not hand-computed: each is what ClickHouse itself returned for
 * {@code toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))} on prod-test for that id. If the table's
 * partition expression ever changes, these assertions are what should fail.
 * <p>
 * The range-boundary cases are the exception and say so: the ids are constructed rather than observed, because the point
 * of each is an {@code id_at} value the column cannot store, which is exactly what no real row has.
 */
class WeeklyPartitionsTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("matches the partition ClickHouse computed")
    void matchesThePartitionClickHouseComputed(String era, UUID id, long expectedPartition) {
        assertThat(WeeklyPartitions.of(List.of(id))).contains(Set.of(expectedPartition));
    }

    /**
     * One row per era the derivation has to get right, each pinned to the value ClickHouse itself returned for that id
     * on prod-test — not to a hand-computed Monday. The eras are not interchangeable samples: an expression that is
     * correct for the ordinary calendar and wrong at the extremes is exactly the {@code toMonday} trap migration 000114
     * was written to escape, so dropping any of the three would weaken the pin rather than tidy it.
     */
    private static Stream<Arguments> matchesThePartitionClickHouseComputed() {
        return Stream.of(
                // The ordinary case: id_at 2026-08-19 (a Wednesday) -> Monday 2026-08-17.
                arguments("ordinary id", UUID.fromString("01a01a75-76de-785e-ae84-8870ed5e6db3"), 20260817L),
                // Long before Opik existed but well after the Unix epoch, and well inside Date32's 1900 floor: an id
                // this old prunes like any other. id_at 1996-02-09 -> Monday 1996-02-05.
                arguments("id from 1996", UUID.fromString("00bfd451-fa93-7c10-9923-88a219a974c8"), 19960205L),
                // Far-future ids are supported, not excluded: a bogus-but-self-consistent timestamp, id_at 2200-01-01 ->
                // Monday 2199-12-30. 4.1% of rows on prod-test look like this; they must still be deletable and prune.
                arguments("far-future id", UUID.fromString("0699eb8a-59dd-7215-8000-03b8d2a8d5e2"), 21991230L));
    }

    @Test
    @DisplayName("a scattered batch yields the exact set, not a range")
    void scatteredBatch() {
        // 1996 and 2026 in one batch. An id_at RANGE over this span selected 2,644 of 3,928 parts on prod-test;
        // the exact set selected 4. This is the reason the predicate is a set.
        assertThat(WeeklyPartitions.of(List.of(
                UUID.fromString("01a01a75-76de-785e-ae84-8870ed5e6db3"),
                UUID.fromString("00bfd451-fa93-7c10-9923-88a219a974c8"))))
                .contains(Set.of(20260817L, 19960205L));
    }

    @Test
    @DisplayName("a non-v7 id disables pruning for the whole batch")
    void nonV7DisablesPruning() {
        // All-or-nothing on purpose: deriving a partition from a non-v7 id reads whatever sits in the timestamp
        // field, and a wrong partition is a SILENTLY skipped delete. Omitting the predicate keeps the old behaviour.
        assertThat(WeeklyPartitions.of(List.of(
                UUID.fromString("01a01a75-76de-785e-ae84-8870ed5e6db3"),
                UUID.fromString("9f527bac-527a-4f92-8875-0fa8af8e4f22")))) // v4
                .isEmpty();
    }

    @Test
    @DisplayName("a single non-v7 id yields no partitions")
    void singleNonV7() {
        assertThat(WeeklyPartitions.of(List.of(UUID.fromString("9f527bac-527a-4f92-8875-0fa8af8e4f22"))))
                .isEmpty();
    }

    @Test
    @DisplayName("duplicate ids in the same week collapse to one partition")
    void duplicatesCollapse() {
        assertThat(WeeklyPartitions.of(List.of(
                UUID.fromString("01a01a75-76de-785e-ae84-8870ed5e6db3"),
                UUID.fromString("01a01a75-6f8e-7f22-9279-ee4f7ca7810d"),
                UUID.fromString("01a01a75-609d-7935-8d22-2dd8dfeb2454"))))
                .contains(Set.of(20260817L));
    }

    @Test
    @DisplayName("an empty batch yields no partitions")
    void emptyBatch() {
        assertThat(WeeklyPartitions.of(List.of())).isEmpty();
    }

    @Test
    @DisplayName("the returned set is immutable, so a delete's partitions cannot be narrowed after derivation")
    void returnedSetIsImmutable() {
        // Not a general hygiene assertion: this set IS the partition list a DELETE binds, so a caller that removed an
        // entry would turn a correct delete into one that matches nothing and reports success.
        var partitions = WeeklyPartitions.of(List.of(
                UUID.fromString("01a01a75-76de-785e-ae84-8870ed5e6db3"),
                UUID.fromString("00bfd451-fa93-7c10-9923-88a219a974c8")))
                .orElseThrow();

        assertThatThrownBy(() -> partitions.remove(20260817L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(partitions).containsExactlyInAnyOrder(20260817L, 19960205L);
    }

    @Test
    @DisplayName("a null batch throws rather than reading as an unprunable one")
    void nullBatchThrows() {
        // The one intolerant case, and deliberately not folded into the empty result above: empty is a documented
        // answer ("emit the unbounded form"), so a caller that lost its batch would get a valid-looking answer, issue a
        // correct-but-unbounded mutation, and never learn it had a bug. Matches every other collection-taking method in
        // this package, all of which are @NonNull.
        assertThatThrownBy(() -> WeeklyPartitions.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ids");
    }

    @Test
    @DisplayName("the last id_at the column can store still prunes")
    void lastRepresentableIdStillPrunes() {
        // id_at 2299-12-31T23:59:59.999 — the last instant DateTime64 represents, so nothing saturates and the two
        // sides agree: DateTime64(0) truncates to 23:59:59, whose Date32 is 2299-12-31 (a Sunday) -> Monday 2299-12-25.
        // The ceiling check must be exclusive at exactly this point, hence a case sitting on it.
        assertThat(WeeklyPartitions.of(List.of(UUID.fromString("0978a65f-77ff-7abc-8000-000000000001"))))
                .contains(Set.of(22991225L));
    }

    @Test
    @DisplayName("an id one millisecond past the id_at ceiling disables pruning")
    void firstUnrepresentableIdDisablesPruning() {
        // id_at 2300-01-01T00:00:00 — one ms past the previous case and outside DateTime64. ClickHouse saturates it to
        // 2299-12-31 23:59:59 and files the row under 22991225, so the honest week this would compute (2300-01-01 is
        // itself a Monday, giving 23000101) is a partition the row is NOT in. Pruning off rather than clamped: matching
        // ClickHouse here would mean reproducing its saturation semantics, for ids that should not exist.
        assertThat(WeeklyPartitions.of(List.of(UUID.fromString("0978a65f-7800-7abc-8000-000000000001"))))
                .isEmpty();
    }

    @Test
    @DisplayName("the largest timestamp a UUIDv7 can carry disables pruning, and does not throw")
    void largestUuidV7TimestampDisablesPruning() {
        // All 48 timestamp bits set: 10889-08-02, the furthest future any UUIDv7 can encode. Read unsigned it is still
        // only ~2.8e14 ms, far inside Instant's range — so the guard is what excludes it, not an exception, and there
        // is no input on which this can throw.
        assertThat(WeeklyPartitions.of(List.of(UUID.fromString("ffffffff-ffff-7abc-8000-000000000001"))))
                .isEmpty();
    }

    @Test
    @DisplayName("one out-of-range id disables pruning for the whole batch")
    void outOfRangeIdDisablesPruningForTheWholeBatch() {
        // Same all-or-nothing rule as a non-v7 id, for the same reason: a set derived from the rest of the batch is a
        // set this row is not in.
        assertThat(WeeklyPartitions.of(List.of(
                UUID.fromString("01a01a75-76de-785e-ae84-8870ed5e6db3"),
                UUID.fromString("ffffffff-ffff-7abc-8000-000000000001"))))
                .isEmpty();
    }

    @Test
    @DisplayName("the earliest id a UUIDv7 can carry is inside Date32, so there is no floor to guard")
    void earliestUuidV7TimestampIsInRange() {
        // All 48 timestamp bits clear: id_at 1970-01-01, the earliest any UUIDv7 can encode (the field is unsigned).
        // Its Monday, 1969-12-29, is 70 years above Date32's 1900 floor, so a below-1900 id_at is unreachable by
        // construction rather than merely untested — which is why `of` guards only the ceiling.
        assertThat(WeeklyPartitions.of(List.of(UUID.fromString("00000000-0000-7abc-8000-000000000001"))))
                .contains(Set.of(19691229L));
    }
}
