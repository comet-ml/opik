package com.comet.opik.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
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
 */
@DisplayName("Trace Mutation SQL Routing")
class TraceMutationSqlRoutingTest {

    /**
     * A mutation and the token it targets. Deliberately narrow: the three statement kinds a {@code Distributed} table
     * rejects, which are exactly the ones that must be routed to the shard.
     */
    private static final Pattern MUTATION_TARGET = Pattern
            .compile("(?i)\\b(DELETE\\s+FROM|ALTER\\s+TABLE|OPTIMIZE\\s+TABLE)\\s+(\\S+)");

    /** Single-line Java string literals, escapes included. Text blocks are covered by the constants scan instead. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");

    /** The names a mutation must never name directly; {@code tracesMutationTable()} decides between them. */
    private static final Set<String> PHYSICAL_TRACE_TABLES = Set.of("traces", "traces_local");

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
                if (TraceMutationSql.namesAPhysicalTraceTable(mutation)) {
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
     * Regression coverage for the detector itself. Every form here is a way of naming a physical trace table that a
     * mutation could legitimately be written in, and each must be flagged — a detector that only recognises the bare,
     * unqualified name is a guard with a hole in it rather than a guard.
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
    })
    @DisplayName("the detector flags every way of naming a physical trace table")
    void detectorFlagsPhysicalTraceTables(String mutation) {
        assertThat(TraceMutationSql.namesAPhysicalTraceTable(mutation))
                .as("`%s` names a physical trace table and must be flagged", mutation)
                .isTrue();
    }

    /**
     * The complement: what the detector must <b>not</b> flag. The resolver placeholder is the permitted form, the shadow
     * and backup tables are not the mutation targets this guard governs, and a same-prefix table must not be caught by a
     * sloppy {@code startsWith}.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM <traces_mutation_table>",
            "DELETE FROM traces_local_v2",
            "DELETE FROM traces_pre_cutover_backup",
            "DELETE FROM trace_threads",
            "DELETE FROM spans",
    })
    @DisplayName("the detector leaves the resolver placeholder and unrelated tables alone")
    void detectorIgnoresPermittedTargets(String mutation) {
        assertThat(TraceMutationSql.namesAPhysicalTraceTable(mutation))
                .as("`%s` must not be flagged", mutation)
                .isFalse();
    }

}
