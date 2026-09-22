package com.comet.opik.domain;

import com.comet.opik.api.Trace;
import com.comet.opik.podam.PodamFactoryUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@code end_time} / {@code ttft} sentinel branch, which the integration suite cannot reach:
 * {@code traceColumnsNonNullable} is false in {@code config-test.yml} and flipping it needs a different
 * app config. Both states are live across environments mid-migration, so the branch the integration
 * tests do not exercise is the one most worth pinning.
 */
class TraceJsonRowMapperTest {

    private static final PodamFactory FACTORY = PodamFactoryUtils.newPodamFactory();
    private static final String USER = "a-user";
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();

    private Trace traceWithout(Instant endTime, Double ttft) {
        return FACTORY.manufacturePojo(Trace.class).toBuilder()
                .endTime(endTime)
                .ttft(ttft)
                .build();
    }

    @Test
    @DisplayName("while the columns are Nullable, an absent end_time and ttft are explicit JSON nulls")
    void nullableColumnsWriteNulls() {
        var row = TraceJsonRowMapper.toJsonRow(traceWithout(null, null), USER, WORKSPACE_ID, Instant.now(),
                false, 10001);

        assertThat(row.get("end_time").isNull()).isTrue();
        assertThat(row.get("ttft").isNull()).isTrue();
    }

    @Test
    @DisplayName("once the columns are non-nullable, the same absences become the epoch and NaN sentinels")
    void nonNullableColumnsWriteSentinels() {
        var row = TraceJsonRowMapper.toJsonRow(traceWithout(null, null), USER, WORKSPACE_ID, Instant.now(),
                true, 10001);

        // Not merely non-null: the epoch is the value epochToNull translates back on read, so a
        // different stamp here would read back as a real end_time rather than an absent one.
        assertThat(row.get("end_time").asText()).isEqualTo("1970-01-01 00:00:00.000000000");
        // Jackson quotes non-finite numbers, so this is the "NaN" text the insert's
        // input_format_json_read_numbers_as_strings exists to accept. Asserted because the whole
        // sentinel round-trip depends on it surviving serialization.
        assertThat(Double.isNaN(row.get("ttft").asDouble())).isTrue();
        assertThat(row.toString()).contains("\"ttft\":\"NaN\"");
    }

    @Test
    @DisplayName("a present end_time and ttft are written the same either side of the toggle")
    void presentValuesAreUnaffectedByTheToggle() {
        var trace = traceWithout(Instant.parse("2026-09-22T10:11:12.123456789Z"), 12.5);

        var nullable = TraceJsonRowMapper.toJsonRow(trace, USER, WORKSPACE_ID, Instant.now(), false, 10001);
        var nonNullable = TraceJsonRowMapper.toJsonRow(trace, USER, WORKSPACE_ID, Instant.now(), true, 10001);

        assertThat(nullable.get("end_time").asText()).isEqualTo("2026-09-22 10:11:12.123456789");
        assertThat(nullable.get("end_time")).isEqualTo(nonNullable.get("end_time"));
        assertThat(nullable.get("ttft").asDouble()).isEqualTo(12.5);
        assertThat(nullable.get("ttft")).isEqualTo(nonNullable.get("ttft"));
    }

    @Test
    @DisplayName("a non-positive truncation size leaves the column out so its DDL default applies")
    void nonPositiveTruncationSizeOmitsTheColumn() {
        var trace = traceWithout(null, null);

        assertThat(TraceJsonRowMapper.toJsonRow(trace, USER, WORKSPACE_ID, Instant.now(), false, 0)
                .has("truncation_threshold")).isFalse();
        assertThat(TraceJsonRowMapper.toJsonRow(trace, USER, WORKSPACE_ID, Instant.now(), false, 10001)
                .get("truncation_threshold").asInt()).isEqualTo(10001);
    }
}
