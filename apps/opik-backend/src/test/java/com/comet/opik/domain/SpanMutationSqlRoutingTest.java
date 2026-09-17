package com.comet.opik.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one span-routing check that cannot be an {@code ArchRule}, plus this family's half of the regression coverage
 * for the shared {@link MutationSql} detector. The spans counterpart of {@link TraceMutationSqlRoutingTest}.
 *
 * <p>{@link SpanMutationRoutingArchTest} holds the rest: the flag is read in one place, and no declared SQL constant
 * names a physical span table. That second rule reflects over the constants, which a custom {@code ArchCondition} can
 * do.
 *
 * <p><b>This one cannot follow, because the SQL it guards never becomes a constant.</b> A mutation assembled at runtime
 * — a {@code StringBuilder} appending {@code "DELETE FROM spans WHERE ("} — exists only as inline string literals.
 * ArchUnit works from bytecode and exposes call graphs rather than string values, so those literals are invisible to it
 * however the rule is written. Reading the source file is the only way to see them, and the declared-constant rule
 * would stay green while such a mutation named whatever it liked.
 *
 * <p><b>What it does not catch, stated so it is not mistaken for airtight.</b> It matches single-line literals, so SQL
 * split across concatenation — {@code "DELETE FROM " + "spans"} — evades it: the first literal has no target and the
 * second no statement keyword. A text block inside a method body is likewise unmatched (text-block <i>constants</i> are
 * covered, but by the reflecting arch rule). Closing those needs either Java parsing, which brings its own false
 * positives, or routing every mutation through a construction API, which is a DAO design change rather than a test.
 * The realistic regression, a single literal per statement, is caught.
 */
class SpanMutationSqlRoutingTest {

    /** Single-line Java string literals, escapes included. Text blocks are covered by the constants scan instead. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");

    private static final Path SPAN_DAO_SOURCE = Path.of("src/main/java/com/comet/opik/domain/SpanDAO.java");

    @Test
    void noInlineStringLiteralMutatesAPhysicalSpanTableByName() throws Exception {
        assertThat(SPAN_DAO_SOURCE)
                .as("SpanDAO source must be readable at %s (relative to apps/opik-backend); if it moved, update this "
                        + "guard rather than dropping the assertion", SPAN_DAO_SOURCE)
                .isRegularFile();

        var source = Files.readString(SPAN_DAO_SOURCE);
        var offenders = new ArrayList<String>();

        var literals = STRING_LITERAL.matcher(source);
        while (literals.find()) {
            var literal = literals.group();
            for (var mutation : MutationSql.SPANS.findMutations(literal)) {
                if (MutationSql.SPANS.targetsATableWithoutTheResolver(mutation)) {
                    offenders.add(literal);
                }
            }
        }

        assertThat(offenders)
                .as("""
                        a mutation assembled from string literals must take its table from \
                        SpanDAO#selectSpansMutationTable rather than embedding a name, so runtime-built SQL is routed \
                        by the same single decision as the templates\
                        """)
                .isEmpty();
    }

    /**
     * Regression coverage for the detector. Every form here is a way a mutation could reach a span table without going
     * through the resolver, and each must be flagged — a detector that recognises only the bare, unqualified name is a
     * guard with a hole in it rather than a guard.
     * <p>
     * Together with its complement below, this is also what keeps the shared detector safe to share: a suite wired to
     * the wrong {@link MutationSql} constant fails on the first case here rather than passing while checking a family
     * it was never pointed at.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM spans",
            "DELETE FROM spans_local",
            "DELETE FROM analytics.spans",
            "DELETE FROM analytics.spans_local",
            "DELETE FROM `spans`",
            "DELETE FROM \"spans_local\"",
            "DELETE FROM `analytics`.`spans`",
            "ALTER TABLE opik.spans_local",
            "OPTIMIZE TABLE spans",
            "DELETE FROM SPANS",
            "DELETE FROM spans;",
            // Span-family tables that are not mutation targets at all.
            "DELETE FROM spans_local_v2",
            "DELETE FROM spans_pre_cutover_backup",
            // Any placeholder other than the resolver's is never bound, so it reaches the server as literal text.
            "DELETE FROM <spans_mutation_tables>",
            "DELETE FROM <spans_table>",
    })
    void detectorFlagsSpanTablesWithoutTheResolver(String mutation) {
        assertThat(MutationSql.SPANS.targetsATableWithoutTheResolver(mutation))
                .as("`%s` targets a span table without the resolver and must be flagged", mutation)
                .isTrue();
    }

    /**
     * The complement. The resolver placeholder is the permitted form, and an inline literal may legitimately mutate an
     * unrelated table — which is why this predicate is narrower than the declared-constant rule, where any target other
     * than the placeholder is wrong.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM <spans_mutation_table>",
            // Unrelated tables: an inline literal may legitimately mutate one, so requiring the placeholder
            // unconditionally would be wrong here (unlike in the declared-constant rule).
            "DELETE FROM traces",
            "DELETE FROM traces_local_v2",
            "DELETE FROM experiment_items",
    })
    void detectorIgnoresPermittedTargets(String mutation) {
        assertThat(MutationSql.SPANS.targetsATableWithoutTheResolver(mutation))
                .as("`%s` must not be flagged", mutation)
                .isFalse();
    }
}
