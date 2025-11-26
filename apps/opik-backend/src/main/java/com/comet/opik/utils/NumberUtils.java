package com.comet.opik.utils;

import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Utility class for number formatting and manipulation operations.
 */
@UtilityClass
public class NumberUtils {

    /**
     * Formats decimal number to 4 decimal places.
     * If the value is an integer (no decimal part), returns as integer string.
     * Otherwise, formats to 4 decimal places with trailing zeros removed.
     *
     * @param value the BigDecimal value to format
     * @return formatted string representation
     */
    public static String formatDecimal(BigDecimal value) {
        if (value.stripTrailingZeros().scale() <= 0) {
            // It's an integer
            return value.toBigInteger().toString();
        }
        return value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
