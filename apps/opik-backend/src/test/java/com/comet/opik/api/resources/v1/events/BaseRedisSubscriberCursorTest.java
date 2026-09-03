package com.comet.opik.api.resources.v1.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.stream.StreamMessageId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function coverage for {@link BaseRedisSubscriber#nextCursor(StreamMessageId)}, the mapping that
 * decides where the next {@code XAUTOCLAIM} scan resumes (OPIK-8240).
 *
 * <p>Separate from {@link BaseRedisSubscriberUnitTest} because these need no Redis mocks at all, and
 * living under that class's Mockito setup would trip strict stubbing.
 */
@DisplayName("BaseRedisSubscriber claim-cursor mapping")
class BaseRedisSubscriberCursorTest {

    @Test
    @DisplayName("Redis's 0-0 end-of-pass reply wraps back to the start of the pending list")
    void endOfPassWrapsToStart() {
        // Compared numerically on purpose. StreamMessageId.MIN and .ALL are wire sentinels that serialize
        // to "-" and "0", and NEITHER is equals() to the StreamMessageId(0, 0) that Redisson parses Redis's
        // literal 0-0 into -- verified against redisson 4.7.0. Matching on the constants would therefore
        // never fire, the cursor would stick at the end of the PEL, and the scan would stop finding
        // anything at all: strictly worse than the always-MIN behaviour this replaces.
        assertThat(BaseRedisSubscriber.nextCursor(new StreamMessageId(0, 0))).isEqualTo(StreamMessageId.MIN);
    }

    @Test
    @DisplayName("A missing next-id is treated as end-of-pass rather than propagating a null")
    void nullNextIdWrapsToStart() {
        assertThat(BaseRedisSubscriber.nextCursor(null)).isEqualTo(StreamMessageId.MIN);
    }

    @Test
    @DisplayName("A mid-scan position is carried forward unchanged, which is what walks past the first window")
    void midScanPositionIsCarriedForward() {
        // Redis caps each XAUTOCLAIM at COUNT * 10 entries EXAMINED, so without carrying this forward the
        // scan only ever sees the first ~100 pending entries and anything behind them is never reclaimed.
        var midScan = new StreamMessageId(1787891543627L, 0);
        assertThat(BaseRedisSubscriber.nextCursor(midScan)).isSameAs(midScan);
    }
}
