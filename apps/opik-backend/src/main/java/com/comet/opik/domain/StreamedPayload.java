package com.comet.opik.domain;

import com.comet.opik.utils.TruncationUtils;
import io.r2dbc.spi.Statement;
import jakarta.annotation.Nullable;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

/**
 * Binds the large, user-supplied columns of a span or trace write ({@code input}, {@code output},
 * {@code metadata} and their slim copies) as streamed row data instead of SQL literals.
 *
 * <p>An {@code INSERT ... SELECT} that inlines them as literals must fit, as a whole statement, in
 * {@code max_query_size} (100,000,000 bytes in {@code config.yml}): the SQL parser holds the full text. A
 * {@code FORMAT Values} row is not parsed as SQL but streamed by the input format, which that limit does not
 * cover. So the single-row write templates in {@link SpanDAO} and {@link TraceDAO} read these columns from
 * {@code FROM input('input String, output String, metadata String, input_slim String, output_slim String') AS payload}
 * and end with {@code FORMAT Values (:input, :output, :metadata, :input_slim, :output_slim)}, which keeps the
 * server-side merge against the stored row while the payload bypasses the parser. Without it, a write whose payload
 * a batch create accepts fails with {@code Code: 62 Max query size exceeded}.
 *
 * <p>All five are always bound, as {@code ''} when absent, because the row always carries all five; a template
 * reads a column only where it would have referenced the parameter. The tuple stays plain string literals so
 * ClickHouse keeps the streaming {@code Values} fast path.
 */
public class StreamedPayload {

    private StreamedPayload() {
    }

    /**
     * Binds the streamed row. A {@code null} value binds {@code ''} and an empty slim; a non-null one gets its slim
     * copy computed here, so callers cannot let the two drift.
     */
    public static void bind(@NonNull Statement statement, @Nullable String input, @Nullable String output,
            @Nullable String metadata) {
        statement.bind("input", StringUtils.defaultString(input))
                .bind("output", StringUtils.defaultString(output))
                .bind("metadata", StringUtils.defaultString(metadata))
                .bind("input_slim", input == null ? "" : TruncationUtils.createSlimJsonString(input))
                .bind("output_slim", output == null ? "" : TruncationUtils.createSlimJsonString(output));
    }
}
