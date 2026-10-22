package com.comet.opik.domain;

import com.comet.opik.utils.TruncationUtils;
import io.r2dbc.spi.Statement;
import org.apache.commons.lang3.StringUtils;

/**
 * Carries the large, user-supplied columns of a span or trace write ({@code input}, {@code output},
 * {@code metadata} and their slim copies) as streamed row data instead of SQL literals.
 *
 * <p>An {@code INSERT ... SELECT} that inlines them as literals must fit, as a whole statement, in
 * {@code max_query_size} (100,000,000 bytes in {@code config.yml}): the SQL parser holds the full text. A
 * {@code FORMAT Values} row is not parsed as SQL but streamed by the input format, which that limit does not
 * cover. So the templates read these columns from {@link #FROM} and end with {@link #VALUES}, which keeps
 * the server-side merge against the stored row while the payload bypasses the parser. Without it, a write
 * whose payload a batch create accepts fails with {@code Code: 62 Max query size exceeded}.
 *
 * <p>All five are always bound, as {@code ''} when absent, because the row always carries all five; a template
 * reads a column only where it would have referenced the parameter. The tuple stays plain string literals so
 * ClickHouse keeps the streaming {@code Values} fast path.
 */
public class StreamedPayload {

    /**
     * {@code FROM} clause for the streamed row; templates read its columns as {@code p.<column>}. Carries its own
     * keyword and newline because a text block strips the trailing space a {@code FROM """ + ...} split relies on.
     */
    public static final String FROM = "FROM input('input String, output String, metadata String, input_slim String, output_slim String') AS p\n";

    /** Trailing data clause; must be the last thing in the statement, after {@code SETTINGS}. */
    public static final String VALUES = "FORMAT Values (:input, :output, :metadata, :input_slim, :output_slim)";

    private StreamedPayload() {
    }

    /**
     * Binds the streamed row. A {@code null} value binds {@code ''} and an empty slim; a non-null one gets its slim
     * copy computed here, so callers cannot let the two drift.
     */
    public static void bind(Statement statement, String input, String output, String metadata) {
        statement.bind("input", StringUtils.defaultString(input))
                .bind("output", StringUtils.defaultString(output))
                .bind("metadata", StringUtils.defaultString(metadata))
                .bind("input_slim", input == null ? "" : TruncationUtils.createSlimJsonString(input))
                .bind("output_slim", output == null ? "" : TruncationUtils.createSlimJsonString(output));
    }
}
