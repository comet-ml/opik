package com.comet.opik.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recognises a <b>mutation</b> in a fragment of SQL and decides whether it names a physical table of one cutover
 * family, rather than the placeholder its DAO binds.
 *
 * <p>One detector, one constant per family. The detection itself — which statement kinds count, and how a target token
 * reduces to a bare table name — is identical for traces and spans, and the only thing that varies is the two values
 * each constant carries: the resolver placeholder, and the set of table names in that family. Splitting it per family
 * would duplicate the reduction rules below, where a missed case does not fail, it silently stops guarding.
 *
 * <p>Shared by both halves of each family's routing guard so there is one implementation of "is this a mutation, and
 * what does it target": {@code TraceMutationRoutingArchTest} / {@code SpanMutationRoutingArchTest} apply it to their
 * DAO's declared SQL constants, and {@code TraceMutationSqlRoutingTest} / {@code SpanMutationSqlRoutingTest} to the
 * inline string literals a runtime-assembled statement would leave in the source. The detector itself is
 * regression-tested by both of the latter, each against its own family — so a suite wired to the wrong constant fails
 * on its very first case rather than passing while checking nothing.
 *
 * <p>Reads are deliberately out of scope: {@code SELECT ... FROM traces} is correct on both topologies, since the
 * unqualified name is the {@code Distributed} wrapper post-cutover and the {@code MergeTree} before it. Only
 * {@code DELETE} / {@code ALTER} / {@code OPTIMIZE} have to move to the shard.
 */
@Getter
@RequiredArgsConstructor
enum MutationSql {

    TRACES("<traces_mutation_table>",
            Set.of("traces", "traces_local", "traces_local_v2", "traces_pre_cutover_backup")),

    SPANS("<spans_mutation_table>",
            Set.of("spans", "spans_local", "spans_local_v2", "spans_pre_cutover_backup"));

    /**
     * A mutation and the token it targets. Deliberately narrow: the three statement kinds a {@code Distributed} table
     * rejects, which are exactly the ones that must be routed to the shard.
     */
    private static final Pattern MUTATION_TARGET = Pattern
            .compile("(?i)\\b(DELETE\\s+FROM|ALTER\\s+TABLE|OPTIMIZE\\s+TABLE)\\s+(\\S+)");

    /** The only target a mutation of this family may name: the placeholder its DAO's resolver binds. */
    private final String resolverPlaceholder;

    /**
     * Every table in the family, across both topologies and the cutover's intermediate states. Wider than the two
     * physical mutation targets on purpose: the {@code _local_v2} shadow and the {@code _pre_cutover_backup} parked
     * copy are not mutation targets at all, so a mutation naming one is wrong in a different way — and equally
     * unbindable.
     */
    private final Set<String> familyTables;

    /**
     * The {@code (statement, target)} pairs in a fragment of SQL, rendered for a failure message.
     *
     * <p>Family-independent — the statement kinds are the same everywhere — but exposed on the constant so a caller
     * names the family once and the whole detection reads from one receiver.
     */
    List<String> findMutations(String sql) {
        var mutations = new ArrayList<String>();
        var matcher = MUTATION_TARGET.matcher(sql);
        while (matcher.find()) {
            mutations.add("%s %s".formatted(matcher.group(1), matcher.group(2)));
        }
        return mutations;
    }

    /**
     * Whether a mutation targets anything other than this family's resolver placeholder.
     * <p>
     * An allowlist rather than a denylist of the two physical names: rejecting only the known-bad names would leave
     * everything else passing, so a template naming the {@code _local_v2} shadow, or carrying a typo'd placeholder like
     * {@code <traces_mutation_tables>}, would satisfy the rule while never being bound — and would then render with the
     * placeholder text intact and fail at the server. Requiring the exact placeholder closes the whole space instead of
     * two points in it.
     */
    boolean targetsAnythingOtherThanTheResolver(String mutation) {
        return !resolverPlaceholder.equals(targetOf(mutation));
    }

    /**
     * Whether a mutation targets a table of this family by any means other than the resolver placeholder.
     *
     * <p>The predicate for SQL assembled at runtime, where the arch rule's blanket "must be the placeholder" cannot
     * apply: an inline literal may legitimately mutate an unrelated table, so {@code DELETE FROM spans} has to pass
     * {@link #TRACES}. Three cases are violations:
     * <ul>
     *   <li>a <b>family table named directly</b> — including the {@code _local_v2} shadow and the
     *   {@code _pre_cutover_backup} copy, which a two-name denylist would let through;</li>
     *   <li>any <b>other placeholder</b> — nothing binds it, so it reaches the server as literal text;</li>
     *   <li>a qualified or quoted form of either, which {@link #normalizeTarget} reduces first.</li>
     * </ul>
     */
    boolean targetsATableWithoutTheResolver(String mutation) {
        var target = cleanTarget(targetOf(mutation));
        if (resolverPlaceholder.equals(target)) {
            return false;
        }
        if (target.startsWith("<")) {
            return true;
        }
        return familyTables.contains(normalizeTarget(target));
    }

    private static String targetOf(String mutation) {
        return mutation.substring(mutation.lastIndexOf(' ') + 1);
    }

    /** An inline literal's target carries the Java closing quote and any statement punctuation; drop both. */
    private static String cleanTarget(String target) {
        return target.replaceAll("[\"\\s;,]+$", "");
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
     * <p>A resolver placeholder normalizes to empty and so is never flagged, which is the whole point: it is the only
     * permitted way to name a mutation's table.
     */
    private static String normalizeTarget(String target) {
        var unquoted = target.replaceAll("[`\"\\[\\]]", "");
        var lastComponent = unquoted.substring(unquoted.lastIndexOf('.') + 1);
        return lastComponent.replaceAll("[^A-Za-z0-9_].*$", "").toLowerCase();
    }
}
