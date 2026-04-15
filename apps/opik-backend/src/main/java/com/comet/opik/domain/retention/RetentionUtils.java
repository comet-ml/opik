package com.comet.opik.domain.retention;

import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.util.UUID;

@UtilityClass
public class RetentionUtils {

    /**
     * Compute the workspace_id range for the given fraction.
     * Splits the UUID hex space (00000000... to ffffffff...) into N equal ranges.
     *
     * @return String[2]: [rangeStart, rangeEnd)
     */
    public static String[] computeWorkspaceRange(int fraction, int totalFractions) {
        long maxVal = 0xFFFFFFFFL + 1; // 2^32
        long rangeSize = maxVal / totalFractions;

        long start = fraction * rangeSize;
        long end = (fraction == totalFractions - 1) ? maxVal : (fraction + 1) * rangeSize;

        String rangeStart = String.format("%08x", start);
        String rangeEnd = (end >= maxVal)
                ? "~" // ASCII 126, sorts after all alphanumeric chars (some workspace_ids are not hex UUIDs)
                : String.format("%08x", end);

        return new String[]{rangeStart, rangeEnd};
    }

    /**
     * Extract the timestamp from a UUID v7's MSB (top 48 bits = epoch millis).
     */
    public static Instant extractInstant(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long epochMilli = msb >>> 16;
        return Instant.ofEpochMilli(epochMilli);
    }

    /**
     * Compare two UUID v7 values by their MSB (timestamp portion).
     */
    public static int compareUUID(UUID a, UUID b) {
        return Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
    }

    /**
     * Check if an exception chain contains a ClickHouse TOO_MANY_ROWS error (Code 158).
     * Used when estimation queries hit the max_rows_to_read profile limit.
     */
    public static boolean isTooManyRowsException(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("Code: " + CH_TOO_MANY_ROWS)) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static final int CH_TOO_MANY_ROWS = 158;
}
