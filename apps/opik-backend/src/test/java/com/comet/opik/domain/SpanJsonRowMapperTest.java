package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the span row shapes the integration suite cannot reach: {@code spanColumnsNonNullable} is false
 * in {@code config-test.yml}, so the epoch / NaN sentinel branch is never exercised there, and the
 * cost-version rule needs a cost the DAO derived rather than one the caller supplied.
 */
class SpanJsonRowMapperTest {

    private static final PodamFactory FACTORY = PodamFactoryUtils.newPodamFactory();
    private static final String USER = "a-user";
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();

    /** Pre-rendered by the DAO once per batch, so the mapper takes it already formatted. */
    private static final Instant NOW_FOR_BATCH_INSTANT = Instant.parse("2026-09-22T08:09:10.111222333Z");
    private static final String NOW_FOR_BATCH = NOW_FOR_BATCH_INSTANT.toString();
    private static final BigDecimal COST = new BigDecimal("0.000000123456");

    private Span span() {
        return FACTORY.manufacturePojo(Span.class).toBuilder().build();
    }

    @Test
    @DisplayName("while the columns are Nullable, an absent end_time and ttft are explicit JSON nulls")
    void nullableColumnsWriteNulls() {
        var span = span().toBuilder().endTime(null).ttft(null).build();

        var row = SpanJsonRowMapper.toJsonRow(span, USER, WORKSPACE_ID, NOW_FOR_BATCH, COST, "", false, 10001);

        assertThat(row.get("end_time").isNull()).isTrue();
        assertThat(row.get("ttft").isNull()).isTrue();
    }

    @Test
    @DisplayName("once the columns are non-nullable, the same absences become the epoch and NaN sentinels")
    void nonNullableColumnsWriteSentinels() {
        var span = span().toBuilder().endTime(null).ttft(null).build();

        var row = SpanJsonRowMapper.toJsonRow(span, USER, WORKSPACE_ID, NOW_FOR_BATCH, COST, "", true, 10001);

        // The instant, not its spelling -- see TraceJsonRowMapperTest for why.
        assertThat(Instant.parse(row.get("end_time").asText())).isEqualTo(Instant.EPOCH);
        assertThat(Double.isNaN(row.get("ttft").asDouble())).isTrue();
        // Jackson quotes non-finite numbers, which is the form
        // input_format_json_read_numbers_as_strings exists to accept.
        assertThat(row.toString()).contains("\"ttft\":\"NaN\"");
    }

    @Test
    @DisplayName("the cost is written as plain text and the version only when the caller supplies one")
    void costIsPlainTextAndVersionIsCallerDecided() {
        var span = span();

        var estimated = SpanJsonRowMapper.toJsonRow(span, USER, WORKSPACE_ID, NOW_FOR_BATCH, COST, "1.1", false,
                10001);
        var supplied = SpanJsonRowMapper.toJsonRow(span, USER, WORKSPACE_ID, NOW_FOR_BATCH, COST, "", false, 10001);

        // toPlainString, not toString: BigDecimal renders this scale as 1.23456E-7, which the Decimal
        // parser rejects.
        assertThat(estimated.get("total_estimated_cost").asText()).isEqualTo("0.000000123456");
        assertThat(estimated.get("total_estimated_cost_version").asText()).isEqualTo("1.1");
        assertThat(supplied.get("total_estimated_cost_version").asText()).isEmpty();
    }

    @Test
    @DisplayName("metadata keeps the binder's toString form rather than the truncating one")
    void metadataMatchesTheBinder() {
        var metadata = JsonUtils.getJsonNodeFromString("{\"a\":1,\"b\":\"two\"}");
        var span = span().toBuilder().metadata(metadata).build();

        var row = SpanJsonRowMapper.toJsonRow(span, USER, WORKSPACE_ID, NOW_FOR_BATCH, COST, "", false, 10001);

        // The asymmetry is deliberate and lives in the binder: input/output go through
        // TruncationUtils.toJsonString, metadata does not.
        assertThat(row.get("metadata").asText()).isEqualTo(metadata.toString());
    }

    @Test
    @DisplayName("a ttft double survives serialization without losing a bit")
    void ttftIsSerializedLosslessly() {
        // Asserted at the mapper so "does the JSON row change the double?" is answered independently of
        // the HTTP layer and ClickHouse.
        double ttft = 1.2583709557071319E9;
        var span = span().toBuilder().ttft(ttft).build();

        var row = SpanJsonRowMapper.toJsonRow(span, USER, WORKSPACE_ID, NOW_FOR_BATCH, COST, "", false, 10001);

        assertThat(row.get("ttft").asDouble()).isEqualTo(ttft);
        // Through the serialized text too, which is what actually reaches ClickHouse.
        assertThat(JsonUtils.getJsonNodeFromString(row.toString()).get("ttft").asDouble()).isEqualTo(ttft);
        // Byte-identical to the R2DBC driver's own rendering, which is the parity requirement.
        assertThat(row.get("ttft").asText()).isEqualTo(String.valueOf(ttft));
    }

    @Test
    @DisplayName("start_time and a supplied last_updated_at are written as the instants given")
    void timestampsAreWrittenAsGiven() {
        var startTime = Instant.parse("2026-09-22T10:11:12.123456789Z");
        var lastUpdatedAt = Instant.parse("2026-09-22T10:11:13.987654Z");
        var span = span().toBuilder().startTime(startTime).lastUpdatedAt(lastUpdatedAt).build();

        var row = SpanJsonRowMapper.toJsonRow(span, USER, WORKSPACE_ID, NOW_FOR_BATCH, COST, "", false, 10001);

        assertThat(Instant.parse(row.get("start_time").asText())).isEqualTo(startTime);
        assertThat(Instant.parse(row.get("last_updated_at").asText())).isEqualTo(lastUpdatedAt);
    }

    @Test
    @DisplayName("an absent last_updated_at falls back to the batch instant, not to a fresh clock")
    void absentLastUpdatedAtUsesTheBatchInstant() {
        var span = span().toBuilder().lastUpdatedAt(null).build();

        var row = SpanJsonRowMapper.toJsonRow(span, USER, WORKSPACE_ID, NOW_FOR_BATCH, COST, "", false, 10001);

        // Exactly the batch value: last_updated_at is the ReplacingMergeTree version column, so a
        // per-row clock would make a retried row win against itself with different bytes.
        assertThat(Instant.parse(row.get("last_updated_at").asText())).isEqualTo(NOW_FOR_BATCH_INSTANT);
    }
}
