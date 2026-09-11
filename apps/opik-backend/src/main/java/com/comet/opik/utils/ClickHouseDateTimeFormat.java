package com.comet.opik.utils;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Canonical ClickHouse {@code DateTime64(<precision>)} text format, in UTC.
 * <p>
 * ClickHouse's {@code FORMAT Values} fast-path parser only recognises a literal in the canonical
 * {@code 'yyyy-MM-dd HH:mm:ss.fff...'} form for {@code DateTime64} cells. Any function expression
 * ({@code parseDateTime64BestEffort(...)}, {@code now64(...)}, {@code if(...)}, {@code ::} cast,
 * etc.) trips the fast-path assertion and increments {@code system.errors} codes 26 / 27 / 43 — the
 * silent counter pollution we observed in production (see OPIK-5694).
 * <p>
 * Use these formatters to produce the canonical text in Java <i>before</i> binding it to the SQL
 * statement, so the rendered SQL contains a plain string literal.
 */
@UtilityClass
public class ClickHouseDateTimeFormat {

    /** Precision 9 — matches {@code DateTime64(9, 'UTC')} columns ({@code start_time}, {@code end_time}). */
    public static final DateTimeFormatter NANOS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS")
            .withZone(ZoneOffset.UTC);

    /** Precision 6 — matches {@code DateTime64(6, 'UTC')} columns ({@code last_updated_at}). */
    public static final DateTimeFormatter MICROS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
            .withZone(ZoneOffset.UTC);

    public static String formatNanos(@NonNull Instant instant) {
        return NANOS.format(instant);
    }

    public static String formatMicros(@NonNull Instant instant) {
        return MICROS.format(instant);
    }
}
