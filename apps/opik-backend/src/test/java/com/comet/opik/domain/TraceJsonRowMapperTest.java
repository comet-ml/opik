package com.comet.opik.domain;

import com.comet.opik.api.Trace;
import com.comet.opik.podam.PodamFactoryUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@code end_time} / {@code ttft} sentinel branch, which the integration suite cannot reach:
 * {@code traceColumnsNonNullable} is false in {@code config-test.yml} and flipping it needs a different
 * app config. Both states are live across environments mid-migration, so the branch the integration
 * tests do not exercise is the one most worth pinning.
 */
class TraceJsonRowMapperTest {

    // Instance, not static: PodamFactory is not fully thread-safe, and instance-per-class is the
    // convention across the service's tests.
    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private final String user = RandomStringUtils.secure().nextAlphanumeric(20);
    private final String workspaceId = UUID.randomUUID().toString();

    /** Pre-rendered by the DAO once per batch, so the mapper takes it already formatted. */
    private final Instant nowForBatchInstant = Instant.now().minusSeconds(
            ThreadLocalRandom.current().nextInt(1, 10_000));
    private final String nowForBatch = nowForBatchInstant.toString();

    private Trace traceWith(Instant endTime, Double ttft) {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .endTime(endTime)
                .ttft(ttft)
                .build();
    }

    @Test
    @DisplayName("while the columns are Nullable, an absent end_time and ttft are explicit JSON nulls")
    void nullableColumnsWriteNulls() {
        var row = TraceJsonRowMapper.toJsonRow(traceWith(null, null), user, workspaceId, nowForBatch,
                false, 10001);

        assertThat(row.get("end_time").isNull()).isTrue();
        assertThat(row.get("ttft").isNull()).isTrue();
    }

    @Test
    @DisplayName("once the columns are non-nullable, the same absences become the epoch and NaN sentinels")
    void nonNullableColumnsWriteSentinels() {
        var row = TraceJsonRowMapper.toJsonRow(traceWith(null, null), user, workspaceId, nowForBatch,
                true, 10001);

        // The instant, not its spelling: date_time_input_format=best_effort accepts either form, so
        // pinning the text would assert a formatting choice rather than the sentinel. Not merely
        // non-null -- the epoch is what epochToNull translates back on read, so a different stamp would
        // read back as a real end_time rather than an absent one.
        assertThat(Instant.parse(row.get("end_time").asText())).isEqualTo(Instant.EPOCH);
        // Jackson quotes non-finite numbers, so this is the "NaN" text the insert's
        // input_format_json_read_numbers_as_strings exists to accept. Asserted because the whole
        // sentinel round-trip depends on it surviving serialization.
        assertThat(Double.isNaN(row.get("ttft").asDouble())).isTrue();
        assertThat(row.toString()).contains("\"ttft\":\"NaN\"");
    }

    @Test
    @DisplayName("a present end_time and ttft are written the same either side of the toggle")
    void presentValuesAreUnaffectedByTheToggle() {
        var trace = traceWith(Instant.parse("2026-09-22T10:11:12.123456789Z"), 12.5);

        var nullable = TraceJsonRowMapper.toJsonRow(trace, user, workspaceId, nowForBatch, false, 10001);
        var nonNullable = TraceJsonRowMapper.toJsonRow(trace, user, workspaceId, nowForBatch, true, 10001);

        assertThat(Instant.parse(nullable.get("end_time").asText()))
                .isEqualTo(Instant.parse("2026-09-22T10:11:12.123456789Z"));
        assertThat(nullable.get("end_time")).isEqualTo(nonNullable.get("end_time"));
        assertThat(nullable.get("ttft").asDouble()).isEqualTo(12.5);
        assertThat(nullable.get("ttft")).isEqualTo(nonNullable.get("ttft"));
    }

    @Test
    @DisplayName("a non-positive truncation size leaves the column out so its DDL default applies")
    void nonPositiveTruncationSizeOmitsTheColumn() {
        var trace = traceWith(null, null);

        assertThat(TraceJsonRowMapper.toJsonRow(trace, user, workspaceId, nowForBatch, false, 0)
                .has("truncation_threshold")).isFalse();
        assertThat(TraceJsonRowMapper.toJsonRow(trace, user, workspaceId, nowForBatch, false, 10001)
                .get("truncation_threshold").asInt()).isEqualTo(10001);
    }

    @Test
    @DisplayName("start_time and a supplied last_updated_at are written as the instants given")
    void timestampsAreWrittenAsGiven() {
        var startTime = Instant.parse("2026-09-22T10:11:12.123456789Z");
        var lastUpdatedAt = Instant.parse("2026-09-22T10:11:13.987654Z");
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .startTime(startTime)
                .lastUpdatedAt(lastUpdatedAt)
                .build();

        var row = TraceJsonRowMapper.toJsonRow(trace, user, workspaceId, nowForBatch, false, 10001);

        // Parsed rather than string-compared: date_time_input_format=best_effort accepts either
        // spelling, so the instant is the contract and the text is not.
        assertThat(Instant.parse(row.get("start_time").asText())).isEqualTo(startTime);
        assertThat(Instant.parse(row.get("last_updated_at").asText())).isEqualTo(lastUpdatedAt);
    }

    @Test
    @DisplayName("an absent last_updated_at falls back to the batch instant, not to a fresh clock")
    void absentLastUpdatedAtUsesTheBatchInstant() {
        var trace = factory.manufacturePojo(Trace.class).toBuilder().lastUpdatedAt(null).build();

        var row = TraceJsonRowMapper.toJsonRow(trace, user, workspaceId, nowForBatch, false, 10001);

        // The batch value exactly. A per-row Instant.now() would be close but never equal, which is the
        // regression this pins: the helper re-runs the mapper on every insert attempt, and
        // last_updated_at is the ReplacingMergeTree version column.
        assertThat(Instant.parse(row.get("last_updated_at").asText())).isEqualTo(nowForBatchInstant);
    }

    @Test
    @DisplayName("writes exactly the columns BATCH_INSERT lists, no more and no fewer")
    void writesExactlyTheExpectedColumns() {
        // The whole-object assertion for a row mapper: comparing every value would restate the mapper,
        // but the column SET is what breaks silently -- a dropped column reads back as a default and a
        // stray one is rejected by ClickHouse at insert time, and neither shows up in a per-field check.
        var row = TraceJsonRowMapper.toJsonRow(factory.manufacturePojo(Trace.class), user, workspaceId,
                nowForBatch, false, 10_001);

        var columns = new HashSet<String>();
        row.fieldNames().forEachRemaining(columns::add);

        assertThat(columns).containsExactlyInAnyOrder(
                "id", "project_id", "workspace_id", "name", "start_time", "end_time", "input", "output",
                "metadata", "tags", "last_updated_at", "error_info", "created_by", "last_updated_by",
                "thread_id", "visibility_mode", "truncation_threshold", "input_slim", "output_slim",
                "ttft", "source", "environment");
    }
}
