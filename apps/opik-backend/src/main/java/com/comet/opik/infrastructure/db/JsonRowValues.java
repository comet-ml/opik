package com.comet.opik.infrastructure.db;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

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
     * Writes {@code value.toString()}, or an explicit JSON null when it is absent.
     *
     * <p>For a <b>Nullable</b> column. Do not use it for a non-nullable column with a DDL default: a
     * JSON null there is rejected unless {@code input_format_null_as_default} happens to be set, and
     * depending on that is how the two write paths drift apart.
     */
    public void putStringOrNull(@NonNull ObjectNode node, @NonNull String field, @Nullable Object value) {
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
    public void putStringOrOmit(@NonNull ObjectNode node, @NonNull String field, @Nullable Object value) {
        if (value != null) {
            node.put(field, value.toString());
        }
    }

    /**
     * Writes an array of strings, empty when {@code values} is absent — not null, since the
     * {@code Array(String)} columns these map to are non-nullable and an empty array is their natural
     * zero value.
     */
    public void putStringArray(@NonNull ObjectNode node, @NonNull String field,
            @Nullable Collection<String> values) {
        var array = node.putArray(field);
        if (values != null) {
            values.forEach(array::add);
        }
    }

    /**
     * Writes a {@code Map(String, String)} as a nested object, empty when {@code values} is absent.
     */
    public void putStringMap(@NonNull ObjectNode node, @NonNull String field,
            @Nullable Map<String, String> values) {
        var object = node.putObject(field);
        if (values != null) {
            values.forEach(object::put);
        }
    }
}
