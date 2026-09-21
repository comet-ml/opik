package com.comet.opik.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cutover backfill's {@code INSERT}/{@code SELECT} mapping check, exercised against crafted statements.
 *
 * <p><b>Why this is a unit test.</b> Every other leg of {@link CutoverSchemaParity} is proven by injecting drift into a
 * real ClickHouse and watching the guard reject it. This one cannot be: what it reads is a <i>shipped file</i>, and
 * shipped cutover SQL is append-only and correct, so there is no topology to put a container into that would make the
 * assertion fire. The only way to show it fires is to hand the parser SQL that breaks it.
 *
 * <p><b>Why it is worth proving.</b> ClickHouse pairs {@code INSERT (...)} with {@code SELECT ...} by <b>position</b>,
 * not by name. A column added to one list and not the other, or at a different offset, sends every subsequent value
 * into the wrong destination column — at cutover time, on real customer data, with no error raised and both tables
 * still perfectly consistent with each other. No table-to-table comparison can see it. Without the cases below, the
 * single guard against that outcome had nothing showing it works.
 *
 * <p>The parse is family-independent, so one constant exercises it; {@code TRACES} is used because its backfill is the
 * one already shipped.
 */
class CutoverBackfillParityTest {

    private static final CutoverSchemaParity PARITY = CutoverSchemaParity.TRACES;

    /**
     * The shape the shipped backfill has: a mix of bare columns and {@code coalesce(...) AS col} sentinel conversions,
     * whose argument commas must not be read as projection separators.
     */
    private static final String WELL_FORMED = """
            INSERT INTO db.traces_local_v2 (id, workspace_id, end_time, ttft)
            SELECT id,
                   workspace_id,
                   coalesce(end_time, toDateTime64('1970-01-01 00:00:00', 6)) AS end_time,
                   coalesce(ttft, toFloat64('nan')) AS ttft
            FROM db.traces
            """;

    @Test
    void acceptsTheShippedShapeOfBareColumnsAndAliasedConversions() {
        assertThat(PARITY.columnListIn(WELL_FORMED))
                .as("the column list must parse in declaration order, with conversion commas left intact")
                .containsExactly("id", "workspace_id", "end_time", "ttft");

        PARITY.assertInsertMatchesSelectIn(WELL_FORMED);
    }

    static Stream<Arguments> positionalMismatches() {
        return Stream.of(
                Arguments.of("a column present in the INSERT list but missing from the SELECT", """
                        INSERT INTO db.traces_local_v2 (id, workspace_id, end_time)
                        SELECT id, workspace_id FROM db.traces
                        """),
                Arguments.of("a column selected but absent from the INSERT list", """
                        INSERT INTO db.traces_local_v2 (id, workspace_id)
                        SELECT id, workspace_id, end_time FROM db.traces
                        """),
                Arguments.of("both lists the same length but in different orders", """
                        INSERT INTO db.traces_local_v2 (id, workspace_id, end_time)
                        SELECT id, end_time, workspace_id FROM db.traces
                        """),
                Arguments.of("an aliased conversion pointed at the wrong destination", """
                        INSERT INTO db.traces_local_v2 (id, end_time, ttft)
                        SELECT id,
                               coalesce(ttft, toFloat64('nan')) AS ttft,
                               coalesce(end_time, toDateTime64('1970-01-01 00:00:00', 6)) AS end_time
                        FROM db.traces
                        """));
    }

    /**
     * The four ways the two lists drift apart. The last is the subtle one: every name appears on both sides, so a
     * set-based comparison passes while each value lands in the other column.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("positionalMismatches")
    void rejectsAnInsertSelectMismatch(String description, String sql) {
        assertThatThrownBy(() -> PARITY.assertInsertMatchesSelectIn(sql))
                .as("%s must be rejected", description)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("select parity");
    }

    static Stream<Arguments> malformedColumnLists() {
        return Stream.of(
                Arguments.of("a repeated column, which shifts every later value one place", """
                        INSERT INTO db.traces_local_v2 (id, workspace_id, id)
                        SELECT id, workspace_id, id FROM db.traces
                        """, "must not repeat"),
                Arguments.of("an expression where a bare column name belongs", """
                        INSERT INTO db.traces_local_v2 (id, lower(workspace_id))
                        SELECT id, lower(workspace_id) FROM db.traces
                        """, "bare column names"),
                Arguments.of("no INSERT statement at all", """
                        SELECT id FROM db.traces
                        """, "no INSERT INTO statement"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedColumnLists")
    void rejectsAMalformedColumnList(String description, String sql, String expectedInMessage) {
        assertThatThrownBy(() -> PARITY.columnListIn(sql))
                .as("%s must be rejected", description)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(expectedInMessage);
    }

    /**
     * An unaliased expression maps positionally like any other entry, so it is legal SQL and would copy correctly —
     * but nothing can verify <i>which</i> column it targets, which is the whole basis of the check above. It fails
     * rather than being trusted.
     */
    @Test
    void rejectsAnUnaliasedExpressionInTheSelect() {
        var sql = """
                INSERT INTO db.traces_local_v2 (id, end_time)
                SELECT id, coalesce(end_time, toDateTime64('1970-01-01 00:00:00', 6)) FROM db.traces
                """;

        assertThatThrownBy(() -> PARITY.assertInsertMatchesSelectIn(sql))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("must name its destination");
    }
}
