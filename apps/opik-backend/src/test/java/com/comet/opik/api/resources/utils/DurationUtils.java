package com.comet.opik.api.resources.utils;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.time.Duration;
import java.time.Instant;

@UtilityClass
public class DurationUtils {

    public static final Double TIME_UNIT = 1_000.0;

    public static Double getDurationInMillisWithSubMilliPrecision(@NonNull Instant startTime, Instant endTime) {
        if (Instant.EPOCH.equals(startTime) || endTime == null) {
            return null;
        }

        long micros = Duration.between(startTime, endTime).toNanos() / TIME_UNIT.longValue();
        return micros / TIME_UNIT;
    }

}
