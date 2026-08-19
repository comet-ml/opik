package com.comet.opik.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link WeeklyPartitions#of}, which derives the weekly partition values a delete batch resolves to so the
 * mutation can prune instead of rewriting every part.
 * <p>
 * The expected values are not hand-computed: each is what ClickHouse itself returned for
 * {@code toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))} on prod-test for that id. If the table's
 * partition expression ever changes, these assertions are what should fail.
 */
class WeeklyPartitionsTest {

    @Test
    @DisplayName("matches the partition ClickHouse computed — ordinary id")
    void ordinaryId() {
        // id_at 2026-08-19 (a Wednesday) -> Monday 2026-08-17
        assertThat(WeeklyPartitions.of(List.of(UUID.fromString("01a01a75-76de-785e-ae84-8870ed5e6db3"))))
                .contains(Set.of(20260817L));
    }

    @Test
    @DisplayName("far-future ids are supported, not excluded")
    void farFutureId() {
        // A bogus-but-self-consistent timestamp: id_at 2200-01-01 -> Monday 2199-12-30.
        // 4.1% of rows on prod-test look like this; they must still be deletable and still prune.
        assertThat(WeeklyPartitions.of(List.of(UUID.fromString("0699eb8a-59dd-7215-8000-03b8d2a8d5e2"))))
                .contains(Set.of(21991230L));
    }

    @Test
    @DisplayName("pre-epoch-era id")
    void oldId() {
        // id_at 1996-02-09 -> Monday 1996-02-05
        assertThat(WeeklyPartitions.of(List.of(UUID.fromString("00bfd451-fa93-7c10-9923-88a219a974c8"))))
                .contains(Set.of(19960205L));
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
}
