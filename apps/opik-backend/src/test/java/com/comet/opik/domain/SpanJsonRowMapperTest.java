package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the span row shapes the integration suite cannot reach: {@code spanColumnsNonNullable} is false
 * in {@code config-test.yml}, so the epoch / NaN sentinel branch is never exercised there, and the
 * cost-version rule needs a cost the DAO derived rather than one the caller supplied.
 */
class SpanJsonRowMapperTest {

    // Instance, not static: PodamFactory is not fully thread-safe, and instance-per-class is the
    // convention across the service's tests.
    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private final String user = RandomStringUtils.secure().nextAlphanumeric(20);
    private final String workspaceId = UUID.randomUUID().toString();

    /** Pre-rendered by the DAO once per batch, so the mapper takes it already formatted. */
    private final Instant nowForBatchInstant = Instant.now().minusSeconds(
            ThreadLocalRandom.current().nextInt(1, 10_000));
    private final String nowForBatch = nowForBatchInstant.toString();
    private final BigDecimal cost = BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble())
            .setScale(12, RoundingMode.HALF_UP);

    private Span span() {
        return factory.manufacturePojo(Span.class);
    }

    @Test
    @DisplayName("while the columns are Nullable, an absent end_time and ttft are explicit JSON nulls")
    void nullableColumnsWriteNulls() {
        var span = span().toBuilder().endTime(null).ttft(null).build();

        var row = SpanJsonRowMapper.toJsonRow(span, user, workspaceId, nowForBatch, cost, "", false, 10001);

        assertThat(row.get("end_time").isNull()).isTrue();
        assertThat(row.get("ttft").isNull()).isTrue();
    }

    @Test
    @DisplayName("once the columns are non-nullable, the same absences become the epoch and NaN sentinels")
    void nonNullableColumnsWriteSentinels() {
        var span = span().toBuilder().endTime(null).ttft(null).build();

        var row = SpanJsonRowMapper.toJsonRow(span, user, workspaceId, nowForBatch, cost, "", true, 10001);

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

        var estimated = SpanJsonRowMapper.toJsonRow(span, user, workspaceId, nowForBatch, cost, "1.1", false,
                10001);
        var supplied = SpanJsonRowMapper.toJsonRow(span, user, workspaceId, nowForBatch, cost, "", false, 10001);

        // toPlainString, not toString: BigDecimal renders this scale as 1.23456E-7, which the Decimal
        // parser rejects.
        assertThat(estimated.get("total_estimated_cost").asText()).isEqualTo(cost.toPlainString());
        assertThat(estimated.get("total_estimated_cost_version").asText()).isEqualTo("1.1");
        assertThat(supplied.get("total_estimated_cost_version").asText()).isEmpty();
    }

    @Test
    @DisplayName("metadata keeps the binder's toString form rather than the truncating one")
    void metadataMatchesTheBinder() {
        var metadata = JsonUtils.getJsonNodeFromString("{\"a\":1,\"b\":\"two\"}");
        var span = span().toBuilder().metadata(metadata).build();

        var row = SpanJsonRowMapper.toJsonRow(span, user, workspaceId, nowForBatch, cost, "", false, 10001);

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

        var row = SpanJsonRowMapper.toJsonRow(span, user, workspaceId, nowForBatch, cost, "", false, 10001);

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

        var row = SpanJsonRowMapper.toJsonRow(span, user, workspaceId, nowForBatch, cost, "", false, 10001);

        assertThat(Instant.parse(row.get("start_time").asText())).isEqualTo(startTime);
        assertThat(Instant.parse(row.get("last_updated_at").asText())).isEqualTo(lastUpdatedAt);
    }

    @Test
    @DisplayName("an absent last_updated_at falls back to the batch instant, not to a fresh clock")
    void absentLastUpdatedAtUsesTheBatchInstant() {
        var span = span().toBuilder().lastUpdatedAt(null).build();

        var row = SpanJsonRowMapper.toJsonRow(span, user, workspaceId, nowForBatch, cost, "", false, 10001);

        // Exactly the batch value: last_updated_at is the ReplacingMergeTree version column, so a
        // per-row clock would make a retried row win against itself with different bytes.
        assertThat(Instant.parse(row.get("last_updated_at").asText())).isEqualTo(nowForBatchInstant);
    }

    @Test
    @DisplayName("writes exactly the columns BULK_INSERT lists, no more and no fewer")
    void writesExactlyTheExpectedColumns() {
        // See TraceJsonRowMapperTest: the column set is the part of the row that breaks silently.
        var row = SpanJsonRowMapper.toJsonRow(span(), user, workspaceId, nowForBatch, cost, "1.1", false,
                10_001);

        var columns = new HashSet<String>();
        row.fieldNames().forEachRemaining(columns::add);

        assertThat(columns).containsExactlyInAnyOrder(
                "id", "project_id", "workspace_id", "trace_id", "parent_span_id", "name", "type",
                "start_time", "end_time", "input", "output", "metadata", "model", "provider",
                "total_estimated_cost", "total_estimated_cost_version", "tags", "usage",
                "last_updated_at", "error_info", "created_by", "last_updated_by", "truncation_threshold",
                "input_slim", "output_slim", "ttft", "source", "environment");
    }
}
