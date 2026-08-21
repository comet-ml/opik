package com.comet.opik.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The text half of the trace mutation routing guard: no trace mutation SQL may name a physical trace table.
 *
 * <p>{@link TraceMutationRoutingArchTest} keeps the routing <i>decision</i> in one method. That is not quite enough on
 * its own — a new mutation could hardcode {@code DELETE FROM traces} without consulting the flag at all, which no
 * call-graph rule can see. So this test reads the SQL itself and requires every mutation to name the resolver's
 * placeholder instead of a table:
 *
 * <ul>
 *   <li>the SQL <b>constants</b> are read reflectively, which covers the text-block templates exactly, with no parsing
 *   guesswork;</li>
 *   <li>the <b>single-line string literals</b> in the source are scanned too, because a mutation assembled at runtime
 *   (as the bounded retention delete is) never becomes a constant. This is the form the pre-OPIK-7772 code took —
 *   {@code tracesDistributedWrapEnabled() ? "DELETE FROM traces_local WHERE (" : "DELETE FROM traces WHERE ("} — and it
 *   is the form this scan exists to reject.</li>
 * </ul>
 *
 * <p>Reads are deliberately not covered: {@code SELECT ... FROM traces} is correct in both topologies, since
 * {@code traces} is the {@code Distributed} wrapper post-cutover and the {@code MergeTree} before it. Only
 * {@code DELETE} / {@code ALTER} / {@code OPTIMIZE} have to move to the shard.
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
    @DisplayName("no SQL constant mutates a physical trace table by name")
    void noSqlConstantMutatesAPhysicalTraceTableByName() throws Exception {
        var offenders = new ArrayList<String>();
        int mutationsScanned = 0;

        for (var field : TraceDAOImpl.class.getDeclaredFields()) {
            if (!isStringConstant(field)) {
                continue;
            }
            field.setAccessible(true);
            var sql = (String) field.get(null);
            if (sql == null) {
                continue;
            }
            for (var mutation : findMutations(sql)) {
                mutationsScanned++;
                if (namesAPhysicalTraceTable(mutation)) {
                    offenders.add("%s -> %s".formatted(field.getName(), mutation));
                }
            }
        }

        assertThat(mutationsScanned)
                .as("""
                        the scan must actually find trace mutations; zero means the SQL moved out of TraceDAOImpl \
                        constants and this guard has quietly stopped guarding anything\
                        """)
                .isPositive();
        assertThat(offenders)
                .as("""
                        every trace mutation must target <traces_mutation_table>, which TraceDAOImpl#tracesMutationTable \
                        resolves from the wrap flag. Naming `traces` directly breaks every cut-over install (a \
                        Distributed table rejects mutations); naming `traces_local` directly breaks every install that \
                        has not cut over (no such table)\
                        """)
                .isEmpty();
    }

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
            for (var mutation : findMutations(literal)) {
                if (namesAPhysicalTraceTable(mutation)) {
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

    /** The {@code (statement, target)} pairs in a fragment of SQL, rendered for a failure message. */
    private static List<String> findMutations(String sql) {
        var mutations = new ArrayList<String>();
        var matcher = MUTATION_TARGET.matcher(sql);
        while (matcher.find()) {
            mutations.add("%s %s".formatted(matcher.group(1), matcher.group(2)));
        }
        return mutations;
    }

    private static boolean namesAPhysicalTraceTable(String mutation) {
        var target = mutation.substring(mutation.lastIndexOf(' ') + 1);
        return PHYSICAL_TRACE_TABLES.contains(stripSqlPunctuation(target).toLowerCase());
    }

    /** A target token can carry trailing SQL or Java punctuation, e.g. {@code traces_local;} or {@code traces"}. */
    private static String stripSqlPunctuation(String target) {
        return target.replaceAll("[^A-Za-z0-9_].*$", "");
    }

    private static boolean isStringConstant(java.lang.reflect.Field field) {
        return Modifier.isStatic(field.getModifiers())
                && Modifier.isFinal(field.getModifiers())
                && field.getType() == String.class;
    }
}
