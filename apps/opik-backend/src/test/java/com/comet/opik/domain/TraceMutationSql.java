package com.comet.opik.domain;

import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recognises a trace <b>mutation</b> in a fragment of SQL and decides whether it names a physical trace table.
 *
 * <p>Shared by the two halves of the routing guard so there is one implementation of "is this a mutation, and what does
 * it target": {@code TraceMutationRoutingArchTest} applies it to the DAO's declared SQL constants, and
 * {@code TraceMutationSqlRoutingTest} to the inline string literals a runtime-assembled statement leaves in the source.
 * The detector itself is regression-tested in the latter.
 *
 * <p>Reads are deliberately out of scope: {@code SELECT ... FROM traces} is correct on both topologies, since
 * {@code traces} is the {@code Distributed} wrapper post-cutover and the {@code MergeTree} before it. Only
 * {@code DELETE} / {@code ALTER} / {@code OPTIMIZE} have to move to the shard.
 */
@UtilityClass
class TraceMutationSql {

    /**
     * A mutation and the token it targets. Deliberately narrow: the three statement kinds a {@code Distributed} table
     * rejects, which are exactly the ones that must be routed to the shard.
     */
    private static final Pattern MUTATION_TARGET = Pattern
            .compile("(?i)\\b(DELETE\\s+FROM|ALTER\\s+TABLE|OPTIMIZE\\s+TABLE)\\s+(\\S+)");

    /** The names a mutation must never name directly; {@code tracesMutationTable()} decides between them. */
    private static final Set<String> PHYSICAL_TRACE_TABLES = Set.of("traces", "traces_local");

    /** The {@code (statement, target)} pairs in a fragment of SQL, rendered for a failure message. */
    static List<String> findMutations(String sql) {
        var mutations = new ArrayList<String>();
        var matcher = MUTATION_TARGET.matcher(sql);
        while (matcher.find()) {
            mutations.add("%s %s".formatted(matcher.group(1), matcher.group(2)));
        }
        return mutations;
    }

    /** The only target a trace mutation may name: the placeholder {@code selectTracesMutationTable} binds. */
    static final String RESOLVER_PLACEHOLDER = "<traces_mutation_table>";

    /**
     * Whether a mutation targets anything other than the resolver placeholder.
     * <p>
     * An allowlist, deliberately, where this began as a denylist of {@code traces} / {@code traces_local}. Rejecting
     * only the two known-bad names left everything else passing: a template naming {@code traces_local_v2}, or carrying
     * a typo'd placeholder like {@code <traces_mutation_tables>}, would satisfy the rule while never being bound by
     * {@code selectTracesMutationTable} — so it would render with the placeholder text intact and fail at the server.
     * Requiring the exact placeholder closes the whole space instead of two points in it.
     */
    static boolean targetsAnythingOtherThanTheResolver(String mutation) {
        return !RESOLVER_PLACEHOLDER.equals(targetOf(mutation));
    }

    private static String targetOf(String mutation) {
        return mutation.substring(mutation.lastIndexOf(' ') + 1);
    }

    /**
     * Every table in the trace family, across both topologies and the cutover's intermediate states. Wider than the two
     * physical mutation targets on purpose: {@code traces_local_v2} and {@code traces_pre_cutover_backup} are not
     * mutation targets at all, so a mutation naming one is wrong in a different way — and equally unbindable.
     */
    private static final Set<String> TRACE_FAMILY_TABLES = Set.of(
            "traces", "traces_local", "traces_local_v2", "traces_pre_cutover_backup");

    /**
     * Whether a mutation targets a trace table by any means other than the resolver placeholder.
     *
     * <p>The predicate for SQL assembled at runtime, where the arch rule's blanket "must be the placeholder" cannot
     * apply: an inline literal may legitimately mutate an unrelated table, so {@code DELETE FROM spans} has to pass.
     * Three cases are violations:
     * <ul>
     *   <li>a <b>trace-family table named directly</b> — including {@code traces_local_v2} and
     *   {@code traces_pre_cutover_backup}, which the earlier two-name denylist let through;</li>
     *   <li>any <b>other placeholder</b>, e.g. a typo'd {@code <traces_mutation_tables>} — nothing binds it, so it
     *   reaches the server as literal text;</li>
     *   <li>a qualified or quoted form of either, which {@link #normalizeTarget} reduces first.</li>
     * </ul>
     */
    static boolean targetsATraceTableWithoutTheResolver(String mutation) {
        var target = cleanTarget(targetOf(mutation));
        if (RESOLVER_PLACEHOLDER.equals(target)) {
            return false;
        }
        if (target.startsWith("<")) {
            return true;
        }
        return TRACE_FAMILY_TABLES.contains(normalizeTarget(target));
    }

    /** An inline literal's target carries the Java closing quote and any statement punctuation; drop both. */
    private static String cleanTarget(String target) {
        return target.replaceAll("[\"\\s;,]+$", "");
    }

    static boolean namesAPhysicalTraceTable(String mutation) {
        var target = mutation.substring(mutation.lastIndexOf(' ') + 1);
        return PHYSICAL_TRACE_TABLES.contains(normalizeTarget(target));
    }

    /**
     * The bare, lower-cased table identifier a mutation targets.
     *
     * <p>Three things have to come off before the comparison, and missing any one of them lets a mutation through:
     * <ul>
     *   <li><b>quoting</b> — ClickHouse accepts {@code `traces`} and {@code "traces"}, which would not match a bare
     *   name;</li>
     *   <li><b>the database qualifier</b> — {@code analytics.traces} must reduce to {@code traces}. Taking the prefix up
     *   to the first non-word character (as this did originally) yields {@code analytics}, so a qualified mutation
     *   escaped detection entirely;</li>
     *   <li><b>trailing punctuation</b> — a target token can carry {@code ;} or a closing quote from the Java literal.
     *   </li>
     * </ul>
     *
     * <p>The resolver placeholder {@code <traces_mutation_table>} normalizes to empty and so is never flagged, which is
     * the whole point: it is the only permitted way to name a mutation's table.
     */
    private static String normalizeTarget(String target) {
        var unquoted = target.replaceAll("[`\"\\[\\]]", "");
        var lastComponent = unquoted.substring(unquoted.lastIndexOf('.') + 1);
        return lastComponent.replaceAll("[^A-Za-z0-9_].*$", "").toLowerCase();
    }
}
