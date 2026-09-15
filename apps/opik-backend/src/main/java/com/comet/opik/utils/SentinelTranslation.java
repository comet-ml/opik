package com.comet.opik.utils;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE;

/**
 * Single translation point between the API contract (Java {@code null} / {@code Optional<T>} ↔ JSON {@code null})
 * and the sentinel values that back the non-nullable analytics columns.
 *
 * <p>{@code Nullable(...)} carries a per-row null-mask file and cannot back a table index, so it is dropped
 * from the high-volume {@code traces}/{@code spans} columns in favour of a collision-free sentinel per type:</p>
 * <ul>
 *     <li>{@code DateTime64} → {@link Instant#EPOCH} ({@code 1970-01-01 00:00:00}); no real event timestamp collides
 *     with the epoch, so it reads as "not yet ended" / "never scored".</li>
 *     <li>{@code Float64} → {@link Double#NaN}; {@code 0} is a legitimate measurement (instant first token,
 *     sub-millisecond span), so {@code NaN} is the only value that cannot collide with real data.</li>
 *     <li>{@code FixedString(36)} → empty string; ClickHouse also surfaces the all-null-byte form
 *     ({@link ValidationUtils#CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE}) from LEFT JOINs, so both read as absent.</li>
 * </ul>
 *
 * <p>Keeping the {@code sentinel ↔ null} mapping in one place mirrors the codebase's existing idiom for {@code ''}
 * -defaulted strings and guarantees every DAO that reads or writes one of these columns
 * inherits the same contract instead of re-deriving it.</p>
 */
@UtilityClass
public class SentinelTranslation {

    /** Sentinel for a non-nullable {@code DateTime64(9)} column ({@code DEFAULT toDateTime64('1970-01-01 00:00:00', 9)}), matching the {@code precision-9} SQL fragments and the current {@code end_time} column. */
    public static final Instant EPOCH_SENTINEL = Instant.EPOCH;

    /** Sentinel for a non-nullable {@code FixedString(36)} column ({@code DEFAULT ''}). */
    public static final String EMPTY_UUID_SENTINEL = "";

    // Outbound: ClickHouse sentinel → API null.

    /**
     * @return {@code null} when the value is absent — {@code null} or the epoch sentinel — otherwise the value itself.
     */
    public static Instant epochToNull(Instant value) {
        return Optional.ofNullable(value)
                .filter(present -> !EPOCH_SENTINEL.equals(present))
                .orElse(null);
    }

    /**
     * @return {@code null} when the value is absent — {@code null} or {@code NaN} — otherwise the value itself.
     * Infinities are passed through unchanged: they signal a data defect, not the absent-value sentinel.
     */
    public static Double nanToNull(Double value) {
        return Optional.ofNullable(value)
                .filter(present -> !present.isNaN())
                .orElse(null);
    }

    /**
     * @return {@code null} when the value is absent — {@code null}, empty, or the all-null-byte
     * {@code FixedString(36)} form — otherwise the value itself.
     */
    public static String emptyUuidToNull(String value) {
        return Optional.ofNullable(value)
                .filter(StringUtils::isNotBlank)
                .filter(present -> !CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE.equals(present))
                .orElse(null);
    }

    /**
     * As {@link #emptyUuidToNull(String)}, parsed to a {@link UUID}: the single call a read path needs for a
     * {@code FixedString(36)} column holding a UUID or the empty sentinel. Going through the sentinel check first is what
     * makes it safe — the driver surfaces the sentinel as 36 NUL characters, which are not blank, so parsing the raw
     * value directly (behind an {@code isBlank} guard, say) throws instead of reading as absent.
     *
     * @return {@code null} when the value is absent, otherwise the parsed UUID.
     * @throws IllegalArgumentException when the value is present but not a UUID.
     */
    public static UUID emptyUuidToNullableUuid(String value) {
        return Optional.ofNullable(emptyUuidToNull(value))
                .map(UUID::fromString)
                .orElse(null);
    }

    // Inbound: API null → ClickHouse sentinel.

    public static Instant nullToEpoch(Instant value) {
        return Optional.ofNullable(value).orElse(EPOCH_SENTINEL);
    }

    public static double nullToNaN(Double value) {
        return Optional.ofNullable(value).orElse(Double.NaN);
    }

    /**
     * @return the empty sentinel when the value is absent — {@code null}, empty, or blank — otherwise the value itself.
     * Blank is folded in (not just {@code null}) to mirror {@link #emptyUuidToNull} and to stop a blank inbound value
     * from being stored as a partial-NUL {@code FixedString(36)} that reads back as neither the sentinel nor a UUID.
     */
    public static String nullToEmptyUuid(String value) {
        return Optional.ofNullable(value)
                .filter(StringUtils::isNotBlank)
                .orElse(EMPTY_UUID_SENTINEL);
    }

    /**
     * As {@link #nullToEmptyUuid(String)} for a {@link UUID}, so a write path binding a {@code FixedString(36)} column
     * does not have to spell out the null check around {@link UUID#toString()}.
     */
    public static String nullToEmptyUuid(UUID value) {
        return Optional.ofNullable(value)
                .map(UUID::toString)
                .orElse(EMPTY_UUID_SENTINEL);
    }
}
