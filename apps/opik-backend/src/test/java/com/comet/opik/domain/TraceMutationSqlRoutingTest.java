package com.comet.opik.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one routing check that cannot be an {@code ArchRule}, plus the regression coverage for the detector both halves
 * share.
 *
 * <p>{@link TraceMutationRoutingArchTest} holds the rest: the flag is read in one place, the routing decision made in
 * one place, and no declared SQL constant names a physical trace table. That third rule reflects over the constants,
 * which a custom {@code ArchCondition} can do.
 *
 * <p><b>This one cannot follow, because the SQL it guards never becomes a constant.</b> A mutation assembled at runtime
 * — as the bounded retention delete was before OPIK-7772, {@code tracesDistributedWrapEnabled() ? "DELETE FROM
 * traces_local WHERE (" : "DELETE FROM traces WHERE ("} — exists only as inline string literals. ArchUnit works from
 * bytecode and exposes call graphs rather than string values, so those literals are invisible to it however the rule is
 * written. Reading the source file is the only way to see them.
 *
 * <p>That makes this the sole guard against the pattern this PR removes coming back, which is why it is kept rather
 * than folded away: the declared-constant rule would stay green while a new {@code StringBuilder} mutation named
 * whatever it liked.
 *
 * <p><b>What it does not catch, stated so it is not mistaken for airtight.</b> It matches single-line literals, so SQL
 * split across concatenation — {@code "DELETE FROM " + "traces"} — evades it: the first literal has no target and the
 * second no statement keyword. A text block inside a method body is likewise unmatched (text-block <i>constants</i> are
 * covered, but by the reflecting arch rule). Closing those needs either Java parsing, which brings its own false
 * positives, or routing every mutation through a construction API, which is a DAO design change rather than a test.
 * The realistic regression — a single literal per branch, as the pre-OPIK-7772 code had — is caught.
 */
@DisplayName("Trace Mutation SQL Routing")
class TraceMutationSqlRoutingTest {

    /** Single-line Java string literals, escapes included. Text blocks are covered by the constants scan instead. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");

    private static final Path TRACE_DAO_SOURCE = Path.of("src/main/java/com/comet/opik/domain/TraceDAO.java");

    @Test
    @DisplayName("no inline string literal mutates a physical trace table by name")
    void noInlineStringLiteralMutatesAPhysicalTraceTableByName() throws Exception {
        assertThat(TRACE_DAO_SOURCE)
                .as("TraceDAO source must be readable at %s (relative to apps/opik-backend); if it moved, update this "
                        + "guard rather than dropping the assertion", TRACE_DAO_SOURCE)
                .isRegularFile();

        var source = Files.readString(TRACE_DAO_SOURCE);
        var offenders = new ArrayList<String>();

        var literals = STRING_LITERAL.matcher(source);
        while (literals.find()) {
            var literal = literals.group();
            for (var mutation : TraceMutationSql.findMutations(literal)) {
                if (TraceMutationSql.targetsATraceTableWithoutTheResolver(mutation)) {
                    offenders.add(literal);
                }
            }
        }

        assertThat(offenders)
                .as("""
                        a mutation assembled from string literals must append \
                        TraceDAOImpl#tracesMutationTable() rather than embedding a table name, so runtime-built SQL is \
                        routed by the same single decision as the templates\
                        """)
                .isEmpty();
    }

    /**
     * Regression coverage for the detector. Every form here is a way a mutation could reach a trace table without going
     * through the resolver, and each must be flagged — a detector that recognises only the bare, unqualified name is a
     * guard with a hole in it rather than a guard.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM traces",
            "DELETE FROM traces_local",
            "DELETE FROM analytics.traces",
            "DELETE FROM analytics.traces_local",
            "DELETE FROM `traces`",
            "DELETE FROM \"traces_local\"",
            "DELETE FROM `analytics`.`traces`",
            "ALTER TABLE opik.traces_local",
            "OPTIMIZE TABLE traces",
            "DELETE FROM TRACES",
            "DELETE FROM traces;",
            // Trace-family tables that are not mutation targets at all. The earlier two-name denylist passed these.
            "DELETE FROM traces_local_v2",
            "DELETE FROM traces_pre_cutover_backup",
            // Any placeholder other than the resolver's is never bound, so it reaches the server as literal text.
            "DELETE FROM <traces_mutation_tables>",
            "DELETE FROM <traces_table>",
    })
    @DisplayName("the detector flags every way of reaching a trace table without the resolver")
    void detectorFlagsTraceTablesWithoutTheResolver(String mutation) {
        assertThat(TraceMutationSql.targetsATraceTableWithoutTheResolver(mutation))
                .as("`%s` targets a trace table without the resolver and must be flagged", mutation)
                .isTrue();
    }

    /**
     * The complement. The resolver placeholder is the permitted form, and an inline literal may legitimately mutate an
     * unrelated table — which is why this predicate is narrower than the declared-constant rule, where any target other
     * than the placeholder is wrong.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM <traces_mutation_table>",
            // Unrelated tables: an inline literal may legitimately mutate one, so requiring the placeholder
            // unconditionally would be wrong here (unlike in the declared-constant rule).
            "DELETE FROM trace_threads",
            "DELETE FROM spans",
            "DELETE FROM spans_local_v2",
    })
    @DisplayName("the detector leaves the resolver placeholder and unrelated tables alone")
    void detectorIgnoresPermittedTargets(String mutation) {
        assertThat(TraceMutationSql.targetsATraceTableWithoutTheResolver(mutation))
                .as("`%s` must not be flagged", mutation)
                .isFalse();
    }

}
