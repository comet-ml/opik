package com.comet.opik.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** The rule alone; FreeFormSqlPostRunCheckTest runs it on ClickHouse's real query log and plans. */
@DisplayName("Free-form SQL post-run policy check")
class FreeFormSqlPolicyCheckTest {

    private static final String USER = "comet_readonly_freeform_sql_user";
    private static final FreeFormSqlSubqueries.SubqueryReads NONE = FreeFormSqlSubqueries.SubqueryReads.UNKNOWN;

    private static FreeFormSqlSubqueries.SubqueryReads scalar(String table) {
        return new FreeFormSqlSubqueries.SubqueryReads(Set.of(table), Set.of(), false);
    }

    private static FreeFormSqlSubqueries.SubqueryReads filter(String table) {
        return new FreeFormSqlSubqueries.SubqueryReads(Set.of(), Set.of(table), false);
    }

    private static FreeFormSqlQueryLogEntry entry(boolean initial, String user, List<String> tables,
            List<String> policies) {
        return FreeFormSqlQueryLogEntry.builder().initial(initial).user(user).tables(tables)
                .policyCoveredTables(policies).build();
    }

    /** An EXPLAIN json plan with the given read nodes, each {@code type|description|filtered}. */
    private static String plan(String... reads) {
        var nodes = Stream.of(reads).map(read -> {
            String[] parts = read.split("\\|");
            String prewhere = parts.length > 2 && parts[2].equals("filtered")
                    ? ", \"Prewhere info\": {\"Row level filter\": {\"Row level filter column\": \"x\"}}"
                    : "";
            return "{\"Node Type\": \"%s\", \"Description\": \"%s\"%s}".formatted(parts[0], parts[1], prewhere);
        }).toList();
        return "[{\"Plan\": {\"Node Type\": \"Expression\", \"Plans\": [%s]}}]".formatted(String.join(", ", nodes));
    }

    static Stream<Arguments> passes() {
        return Stream.of(
                arguments("top-level read, policy logged", List.of(entry(true, USER, List.of("opik.traces"),
                        List.of("opik.traces"))), plan("ReadFromMergeTree|opik.traces|filtered"), NONE),
                arguments("CTE read: no policy logged, filtered in the plan", List.of(entry(true, USER,
                        List.of("opik.spans"), List.of())), plan("ReadFromMergeTree|opik.spans|filtered"), NONE),
                arguments("IN / EXISTS read: in neither source, not scalar", List.of(entry(true, USER,
                        List.of("opik.traces", "opik.feedback_scores"), List.of("opik.traces"))),
                        plan("ReadFromMergeTree|opik.traces|filtered"), filter("opik.feedback_scores")),
                arguments("Distributed wrapper covered by its local read", List.of(
                        entry(true, USER, List.of("opik.traces"), List.of()),
                        entry(false, "default", List.of("opik.traces_local"), List.of("opik.traces_local"))),
                        plan("ReadFromRemote|Read from remote replica"), NONE),
                arguments("Distributed wrapper covered by its local read filtered in the plan", List.of(
                        entry(true, USER, List.of("opik.traces", "opik.traces_local"), List.of())),
                        plan("ReadFromMergeTree|opik.traces_local|filtered"), NONE),
                arguments("tables outside the database and row generators", List.of(entry(true, USER,
                        List.of("system.one"), List.of())), plan("ReadFromSystemOne|system.one"), NONE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("reads shown to be under their policy pass")
    void passes(String name, List<FreeFormSqlQueryLogEntry> entries, String plan,
            FreeFormSqlSubqueries.SubqueryReads subqueryReads) {
        assertThat(FreeFormSqlPolicyCheck.violation("opik", USER, entries, plan, subqueryReads)).isEmpty();
    }

    static Stream<Arguments> fails() {
        var traces = List.of(entry(true, USER, List.of("opik.traces"), List.of("opik.traces")));
        return Stream.of(
                arguments("a planned read without its row filter", traces, plan("ReadFromMergeTree|opik.traces"),
                        NONE, "opik.traces", "read without a row policy in the plan"),
                arguments("a count answered from metadata", List.of(entry(true, USER, List.of(), List.of())),
                        plan("ReadFromPreparedSource|Optimized trivial count"), NONE, "Optimized trivial count",
                        "unverifiable ReadFromPreparedSource read"),
                arguments("a scalar subquery read, in neither source", List.of(entry(true, USER,
                        List.of("opik.traces", "opik.feedback_scores"), List.of("opik.traces"))),
                        plan("ReadFromMergeTree|opik.traces|filtered"), scalar("opik.feedback_scores"),
                        "opik.feedback_scores", "read without a row policy"),
                arguments("a read missing from the plan that the query tree does not show under IN or EXISTS",
                        List.of(entry(true, USER, List.of("opik.traces", "opik.feedback_scores"),
                                List.of("opik.traces"))),
                        plan("ReadFromMergeTree|opik.traces|filtered"), NONE, "opik.feedback_scores",
                        "read without a row policy"),
                arguments("a read under both IN and a scalar subquery", List.of(entry(true, USER,
                        List.of("opik.traces", "opik.feedback_scores"), List.of("opik.traces"))),
                        plan("ReadFromMergeTree|opik.traces|filtered"),
                        new FreeFormSqlSubqueries.SubqueryReads(Set.of("opik.feedback_scores"),
                                Set.of("opik.feedback_scores"), false),
                        "opik.feedback_scores", "read without a row policy"),
                arguments("a wrapper read on a shard is not covered by another shard's local read", List.of(
                        entry(true, USER, List.of("opik.traces"), List.of()),
                        entry(false, "default", List.of("opik.traces_local"), List.of("opik.traces_local")),
                        entry(false, "default", List.of("opik.traces"), List.of())),
                        plan("ReadFromRemote|Read from remote replica"), NONE, "opik.traces",
                        "read without a row policy on a shard"),
                arguments("one shard skipped the policy another applied", List.of(
                        entry(true, USER, List.of("opik.traces"), List.of()),
                        entry(false, "default", List.of("opik.traces_local"), List.of("opik.traces_local")),
                        entry(false, "default", List.of("opik.traces_local"), List.of())),
                        plan("ReadFromRemote|Read from remote replica"), NONE, "opik.traces_local",
                        "read without a row policy on a shard"),
                arguments("a read the shard query nests, in neither source", List.of(
                        entry(true, USER, List.of("opik.traces"), List.of()),
                        entry(false, "default", List.of("opik.traces_local", "opik.feedback_scores"),
                                List.of("opik.traces_local"))),
                        plan("ReadFromRemote|Read from remote replica"), NONE, "opik.feedback_scores",
                        "read without a row policy on a shard"),
                arguments("no log entry as the account", List.of(entry(true, "default", List.of(), List.of())),
                        plan(), NONE, "", "no query log entry for the query as " + USER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("anything else is a violation, naming what was not covered and why")
    void fails(String name, List<FreeFormSqlQueryLogEntry> entries, String plan,
            FreeFormSqlSubqueries.SubqueryReads subqueryReads, String table, String reason) {
        assertThat(FreeFormSqlPolicyCheck.violation("opik", USER, entries, plan, subqueryReads))
                .contains(FreeFormSqlPolicyViolation.builder().table(table).reason(reason).build());
    }

    static Stream<Arguments> invalidViolations() {
        return Stream.of(
                arguments(null, "read without a row policy", "table must not be null"),
                arguments("opik.traces", null, "reason must not be blank"),
                arguments("opik.traces", "", "reason must not be blank"),
                arguments("opik.traces", "   ", "reason must not be blank"));
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("a violation needs a table, empty when no log entry was found, and a reason")
    void invalidViolations(String table, String reason, String message) {
        assertThatThrownBy(() -> FreeFormSqlPolicyViolation.builder().table(table).reason(reason).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    @ParameterizedTest
    @CsvSource(value = {"'', true", "opik.traces, false"})
    @DisplayName("only the empty table marks a missing log entry")
    void missingLog(String table, boolean missing) {
        assertThat(FreeFormSqlPolicyViolation.builder().table(table).reason("r").build().missingLog())
                .isEqualTo(missing);
    }
}
