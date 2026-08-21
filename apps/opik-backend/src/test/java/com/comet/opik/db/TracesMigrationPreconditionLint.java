package com.comet.opik.db;

import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The static lint decision for one ClickHouse migration file: does a migration that mutates {@code traces} or
 * {@code traces_local} carry the topology guard the playbook requires?
 *
 * <p>Pure and free of I/O on purpose. {@link TracesMigrationPreconditionLintTest} runs it over the shipped migrations
 * directory <i>and</i> over inline fixtures — the latter matters because no shipped migration after the splice point
 * mutates a trace table today, so a directory-only test never reaches the interesting branch and would pass no matter
 * what these patterns did.
 *
 * <p><b>Per changeset, not per file.</b> The guard has to sit on the changeset that performs the mutation. A file-level
 * search is satisfied by a guard on some <i>other</i> changeset in the same file, by an unguarded mutation added
 * alongside a guarded one, and by prose in the header that merely mentions a precondition — none of which guards
 * anything. So the file is split on {@code --changeset} first and each changeset judged on its own body; the header,
 * being before the first changeset, can satisfy nothing.
 *
 * <p><b>Both branches, not one.</b> A single {@code expectedResult:0} changeset is a valid guard and still wrong: it
 * runs pre-cutover and is recorded {@code MARK_RAN} post-cutover, so a cut-over install silently never gets the change
 * while its ledger says otherwise. A trace-table migration must ship the complementary pair.
 *
 * <p>Matching follows Liquibase's own leniency ({@code --preconditions} and {@code -- preconditions} both parse), so the
 * lint agrees with the parser rather than with a stricter idea of the syntax.
 */
@UtilityClass
class TracesMigrationPreconditionLint {

    /**
     * A statement mutating one of the trace tables. Reads are excluded deliberately — {@code SELECT ... FROM traces}
     * is correct on both topologies.
     * <p>
     * {@code traces_local_v2} is included, which is not obvious: the shadow looks single-topology, but the cutover
     * <i>renames it away</i>, so an unguarded shadow {@code ALTER} added after the splice point runs against a table
     * that no longer exists on any cut-over install and fails the migration outright. It needs the same branch
     * treatment as the others.
     */
    private static final Pattern TRACES_MUTATION = Pattern.compile("(?im)^\\s*"
            + "(?:(?:ALTER|OPTIMIZE)\\s+TABLE|DELETE\\s+FROM)\\s+"
            + "(?:\\$\\{ANALYTICS_DB_DATABASE_NAME}\\.)?(traces|traces_local|traces_local_v2)\\b");

    /**
     * The changeset header, used to split a file. Group 1 is {@code author:id}; any trailing Liquibase attributes
     * ({@code id:}, {@code context:}, {@code labels:}, {@code runOnChange:}, {@code splitStatements:}) are matched and
     * ignored. Anchoring after {@code author:id} would miss them — 26 shipped changesets already carry {@code id:} —
     * and an unmatched header means an unparsed file, which {@link #problems} treats as a failure rather than a pass.
     */
    private static final Pattern CHANGESET = Pattern.compile("(?im)^\\s*--\\s*changeset\\s+(\\S+).*$");

    /** {@code onFail:MARK_RAN}, so the branch that does not apply is recorded rather than retried. */
    private static final Pattern MARK_RAN = Pattern.compile("(?im)^\\s*--\\s*preconditions\\b.*\\bonFail:MARK_RAN\\b");

    /**
     * {@code onError:HALT}, so a precondition that cannot be evaluated stops the migration instead of falling through
     * to a guessed topology. The playbook lists it among the four load-bearing details, so the lint requires it.
     */
    private static final Pattern ON_ERROR_HALT = Pattern
            .compile("(?im)^\\s*--\\s*preconditions\\b.*\\bonError:HALT\\b");

    /**
     * The topology check itself. Group 1 is the expected result, which is what distinguishes the pre-cutover branch
     * ({@code 0} — no {@code traces_local}) from the post-cutover one ({@code 1}).
     */
    private static final Pattern TOPOLOGY_CHECK = Pattern.compile(
            "(?im)^\\s*--\\s*precondition-sql-check\\s+expectedResult:(\\d+)\\b.*\\btraces_local\\b.*$");

    private static final Set<String> REQUIRED_BRANCHES = Set.of("0", "1");

    private record ChangeSet(String name, String body) {
    }

    /**
     * Problems found in {@code sql}, empty when the migration is either topology-aware or irrelevant to trace tables.
     * Messages name the file and changeset so a failure points at the edit that caused it.
     */
    static List<String> problems(String fileName, String sql) {
        var problems = new ArrayList<String>();
        var guardedBranches = new LinkedHashSet<String>();
        boolean mutatesTraceTable = false;

        var changeSets = changeSets(sql);

        // "Parsed nothing" must never read as "nothing wrong". If the file mutates a trace table but no changeset
        // header was recognised, the checks below would all be skipped and the migration would pass unexamined.
        if (changeSets.isEmpty() && TRACES_MUTATION.matcher(stripLineComments(sql)).find()) {
            return List.of(("%s: mutates a trace table but no `--changeset` header could be parsed, so it cannot be "
                    + "checked — fix the header rather than leaving the migration unguarded").formatted(fileName));
        }

        for (var changeSet : changeSets) {
            if (!TRACES_MUTATION.matcher(stripLineComments(changeSet.body())).find()) {
                continue;
            }
            mutatesTraceTable = true;

            var check = TOPOLOGY_CHECK.matcher(changeSet.body());
            boolean guarded = MARK_RAN.matcher(changeSet.body()).find()
                    && ON_ERROR_HALT.matcher(changeSet.body()).find()
                    && check.find();
            if (guarded) {
                guardedBranches.add(check.group(1));
            } else {
                problems.add(("%s: changeset '%s' mutates a trace table without a complete topology guard — it needs "
                        + "`--preconditions onFail:MARK_RAN onError:HALT` and a `--precondition-sql-check "
                        + "expectedResult:N ... traces_local` on the changeset itself")
                        .formatted(fileName, changeSet.name()));
            }
        }

        if (mutatesTraceTable && problems.isEmpty() && !guardedBranches.containsAll(REQUIRED_BRANCHES)) {
            problems.add(("%s: a trace-table migration must ship BOTH complementary branches (expectedResult:0 for "
                    + "pre-cutover and expectedResult:1 for post-cutover); found only %s, so one topology would be "
                    + "recorded MARK_RAN and never receive the change").formatted(fileName, guardedBranches));
        }

        return problems;
    }

    /**
     * The file split into changesets. Anything before the first {@code --changeset} is the file header and is dropped:
     * header prose cannot guard a mutation, and including it is exactly how a file-level search gets fooled.
     */
    private static List<ChangeSet> changeSets(String sql) {
        var matcher = CHANGESET.matcher(sql);
        var names = new ArrayList<String>();
        var headerStarts = new ArrayList<Integer>();
        var headerEnds = new ArrayList<Integer>();

        while (matcher.find()) {
            names.add(matcher.group(1));
            headerStarts.add(matcher.start());
            headerEnds.add(matcher.end());
        }

        var changeSets = new ArrayList<ChangeSet>();
        for (int i = 0; i < names.size(); i++) {
            int bodyEnd = i + 1 < headerStarts.size() ? headerStarts.get(i + 1) : sql.length();
            changeSets.add(new ChangeSet(names.get(i), sql.substring(headerEnds.get(i), bodyEnd)));
        }
        return changeSets;
    }

    private static String stripLineComments(String sql) {
        return sql.lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment < 0 ? line : line.substring(0, comment);
                })
                .collect(Collectors.joining("\n"));
    }
}
