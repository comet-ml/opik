package com.comet.opik.domain.topics;

import org.apache.commons.lang3.RandomUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that stands still until a test moves it, so every deadline, time left and age the tick uses is exact. */
final class TopicsTestClock extends Clock {

    private volatile Instant now;

    TopicsTestClock(Instant now) {
        this.now = now;
    }

    /** A random instant up to about 2096, so a test never depends on the wall clock's date. */
    static Instant randomInstant() {
        return Instant.ofEpochSecond(RandomUtils.secure().randomLong(0, 4_000_000_000L));
    }

    void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
