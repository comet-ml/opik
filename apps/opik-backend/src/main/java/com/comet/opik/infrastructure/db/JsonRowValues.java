package com.comet.opik.infrastructure.db;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import jakarta.annotation.Nullable;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/**
 * Value helpers shared by the {@code JSONEachRow} row mappers behind
 * {@code bulkInsert.v2ClientEnabled}.
 *
 * <p>Every converted write path hits the same question per column: an absent value is sometimes a JSON
 * null, sometimes an omitted field, and the two are not interchangeable. A <b>Nullable</b> column wants
 * an explicit null — omitting the field would make it take its DDL default instead, which is a
 * different cell. A non-nullable column with a DEFAULT wants the field omitted, so the server stamps
 * it. Spelling that out per column is what these methods remove.
 *
 * <p>They deliberately do <b>not</b> cover the sentinel columns. Those key off
 * {@code DatabaseAnalyticsDataModelConfig}, so an absent value becomes an epoch or a NaN rather than a
 * null, and {@link com.comet.opik.utils.SentinelTranslation} already owns that translation.
 */
@UtilityClass
public class JsonRowValues {

    /**
     * A blank column name is as wrong as a null one -- {@code node.put("", value)} produces a row
     * ClickHouse rejects with a message naming no column, which is a long way from the call site.
     * {@code @NonNull} cannot see that, so it is checked rather than annotated.
     */
    private void checkField(String field) {
        Preconditions.checkArgument(StringUtils.isNotBlank(field), "field must not be blank");
    }

    /**
     * Writes {@code value.toString()}, or an explicit JSON null when it is absent.
     *
     * <p>For a <b>Nullable</b> column. Do not use it for a non-nullable column with a DDL default: a
     * JSON null there is rejected unless {@code input_format_null_as_default} happens to be set, and
     * depending on that is how the two write paths drift apart.
     */
    public void putStringOrNull(@NonNull ObjectNode node, String field, @Nullable Object value) {
        checkField(field);
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.toString());
        }
    }

    /**
     * Writes {@code value.toString()}, or omits the field entirely when it is absent, letting the
     * column's DDL default stamp it.
     *
     * <p>For a non-nullable column with a DEFAULT. Requires
     * {@code input_format_defaults_for_omitted_fields}, which {@link JsonEachRowBulkInsert} sets per
     * request.
     */
    public void putStringOrOmit(@NonNull ObjectNode node, String field, @Nullable Object value) {
        checkField(field);
        if (value != null) {
            node.put(field, value.toString());
        }
    }

    /**
     * Writes an array of strings, empty when {@code values} is absent — not null, since the
     * {@code Array(String)} columns these map to are non-nullable and an empty array is their natural
     * zero value.
     */
    public void putStringArray(@NonNull ObjectNode node, String field,
            @Nullable Collection<String> values) {
        checkField(field);
        var array = node.putArray(field);
        if (values != null) {
            values.forEach(array::add);
        }
    }

    /**
     * Writes a {@code Map(String, String)} as a nested object, empty when {@code values} is absent.
     */
    public void putStringMap(@NonNull ObjectNode node, String field,
            @Nullable Map<String, String> values) {
        checkField(field);
        var object = node.putObject(field);
        if (values != null) {
            values.forEach(object::put);
        }
    }

    /**
     * Writes a {@code double} so the {@code Float64} ClickHouse stores is bit-for-bit the value given.
     *
     * <p>The obvious {@code node.put(field, value)} writes Jackson's shortest round-tripping decimal.
     * That is exact for a correctly-rounded reader, but ClickHouse's JSON float parse can land 1 ULP
     * away, so the value read back is not the value handed in — a divergence the R2DBC path does not
     * have, since its driver transmits the double in binary. {@link BigDecimal#BigDecimal(double)} is
     * the exact binary value expanded in decimal, which leaves the parser no rounding decision: the
     * only {@code Float64} that text can name is the one we started from.
     *
     * <p>Two cases keep the plain double form instead, because {@code BigDecimal} cannot represent
     * them:
     * <ul>
     * <li><b>Non-finite</b> — there is no {@code BigDecimal} for NaN or an infinity. Jackson quotes
     * them, which is what the insert's {@code input_format_json_read_numbers_as_strings} accepts.</li>
     * <li><b>Zero</b> — {@code BigDecimal} has no signed zero, so {@code new BigDecimal(-0.0)}
     * canonicalizes to {@code 0} and the sign is lost. {@code value == 0.0} is true for both signs, so
     * both take this branch and Jackson writes {@code -0.0} with its sign intact.</li>
     * </ul>
     */
    public void putDoubleExact(@NonNull ObjectNode node, String field, double value) {
        checkField(field);

        if (!Double.isFinite(value) || value == 0.0) {
            node.put(field, value);
        } else {
            node.put(field, new BigDecimal(value));
        }
    }
}
