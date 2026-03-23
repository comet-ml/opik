package com.comet.opik.domain.retention;

import lombok.experimental.UtilityClass;

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
}
