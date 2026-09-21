package com.comet.opik.db;

import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The static lint decision for one ClickHouse migration file: does a migration that mutates a cutover family's tables
 * carry the topology guard the playbook requires?
 *
 * <p>Pure and free of I/O on purpose. Each family's {@code *MigrationPreconditionLintTest} runs it over the shipped
 * migrations directory <i>and</i> over inline fixtures — the latter matters because no shipped migration after either
 * splice point mutates one of these tables today, so a directory-only test never reaches the interesting branch and
 * would pass no matter what these patterns did.
 *
 * <p><b>One mechanism, one constant per family</b>, as {@code MutationSql} and {@link CutoverSchemaParity} are: the
 * parsing, the changeset splitting and every rule below are identical for traces and spans, and only the table names
 * and the shard the guard interrogates differ. Duplicating the patterns per family would mean a pattern fixed in one
 * copy and not the other — and a lint that stops matching does not fail, it silently stops guarding.
 *
 * <p><b>Per changeset, not per file.</b> The guard has to sit on the changeset that performs the mutation. A file-level
 * search is satisfied by a guard on some <i>other</i> changeset in the same file, by an unguarded mutation added
 * alongside a guarded one, and by prose in the header that merely mentions a precondition — none of which guards
 * anything. So the file is split on {@code --changeset} first and each changeset judged on its own body; the header,
 * being before the first changeset, can satisfy nothing.
 *
 * <p><b>Both branches, not one.</b> A single {@code expectedResult:0} changeset is a valid guard and still wrong: it
 * runs pre-cutover and is recorded {@code MARK_RAN} post-cutover, so a cut-over install silently never gets the change
 * while its ledger says otherwise. A migration mutating one of these tables must ship the complementary pair.
 *
 * <p>Matching follows Liquibase's own leniency ({@code --preconditions} and {@code -- preconditions} both parse), so the
 * lint agrees with the parser rather than with a stricter idea of the syntax.
 */
@Getter
enum CutoverMigrationPreconditionLint {

    TRACES("trace", "traces", "traces_local", "000114_recreate_traces_local_v2_id_at_datetime64.sql"),

    SPANS("span", "spans", "spans_local", "000116_apply_spans_local_v2_real_data_codec_refinements.sql");

    /**
     * The changeset header, used to split a file. Group 1 is {@code author:id}; any trailing Liquibase attributes
     * ({@code id:}, {@code context:}, {@code labels:}, {@code runOnChange:}, {@code splitStatements:}) are matched and
     * ignored. Anchoring after {@code author:id} would miss the many shipped changesets that carry {@code id:},
     * and an unmatched header means an unparsed file, which {@link #problems} treats as a failure rather than a pass.
     */
    private static final Pattern CHANGESET = Pattern.compile("(?im)^\\s*--\\s*changeset\\s+(\\S+).*$");

    /** {@code onFail:MARK_RAN}, so the branch that does not apply is recorded rather than retried. */
    private static final Pattern MARK_RAN = Pattern.compile("(?im)^\\s*--\\s*preconditions\\b.*\\bonFail:MARK_RAN\\b");

    /**
     * {@code onError:HALT}, so a precondition that cannot be evaluated stops the migration instead of falling through
     * to a guessed topology. The playbook lists it among the load-bearing details, so the lint requires it.
     */
    private static final Pattern ON_ERROR_HALT = Pattern
            .compile("(?im)^\\s*--\\s*preconditions\\b.*\\bonError:HALT\\b");

    private static final Set<String> REQUIRED_BRANCHES = Set.of("0", "1");

    /**
     * Statements that destroy, move or rewrite a whole table, or mutate its rows. None of them belongs in a migration
     * during the mixed-fleet window: post-cutover the {@code Distributed} wrapper rejects row mutations outright, and a
     * structural change rides the successor's table definition rather than an in-window {@code ALTER}.
     */
    private static final String DESTRUCTIVE_KIND = "(?:(?:OPTIMIZE|DROP|TRUNCATE|RENAME|ATTACH|DETACH)\\s+TABLE|DELETE\\s+FROM|EXCHANGE\\s+TABLES)";

    /** Everything above plus the one kind a migration may legitimately use: {@code ALTER TABLE}. */
    private static final String ANY_MUTATION_KIND = "(?:ALTER\\s+TABLE|" + DESTRUCTIVE_KIND + ")";

    /**
     * One statement kind against one set of table names.
     *
     * <p>{@code IF [NOT] EXISTS} sits between the keyword and the table in {@code DROP}, {@code TRUNCATE},
     * {@code ATTACH} and {@code DETACH}, so a pattern that goes straight from keyword to table name misses every
     * guarded-looking {@code DROP TABLE IF EXISTS <table>}. The database qualifier is matched quoted or unquoted —
     * {@code analytics.spans}, {@code `${...}`.`spans`}, {@code "spans"} — because anchoring on the
     * {@code ${ANALYTICS_DB_DATABASE_NAME}} prefix alone would let a qualified or quoted mutation through. The trailing
     * lookahead keeps neighbouring tables out: {@code spans_attachments} matches no alternative followed by a
     * non-word character.
     */
    private static String statement(String kinds, String tables) {
        return "(?:" + kinds + "\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?"
                + "(?:[`\"]?[A-Za-z0-9_$.{}]+[`\"]?\\.)?"
                + "[`\"]?(?:" + tables + ")[`\"]?(?![A-Za-z0-9_]))";
    }

    /**
     * The column, index or projection a statement adds, drops or modifies. Group 1 is the identifier, which is what
     * {@link #assertBranchesTouchTheSameObjects} compares across the two branches.
     *
     * <p>Settings and TTLs are deliberately absent: {@code MODIFY SETTING} and {@code MODIFY TTL} name no object, and
     * both are legitimately allowed to differ between a shard and the table it will replace.
     */
    private static final Pattern MUTATED_OBJECT = Pattern.compile("(?i)\\b(?:ADD|DROP|MODIFY|MATERIALIZE|CLEAR)\\s+"
            + "(?:COLUMN|INDEX|PROJECTION)\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?[`\"]?([A-Za-z_][A-Za-z0-9_]*)[`\"]?");

    /** A {@code /* ... *}{@code /} block, non-greedy and spanning lines. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");

    /**
     * A line that looks like a changeset header but does not parse as one — either naming nothing at all, or a token
     * that is not {@code author:id}. Liquibase would not treat either as a changeset, so everything below it belongs to
     * the <i>previous</i> changeset and inherits its guard, while {@link #changeSets} splits on it and judges it as a
     * changeset of its own.
     *
     * <p>That divergence is the danger, and it runs in the direction that passes: a file whose second header reads
     * {@code --changeset bogus} looks to this lint like a correctly paired pre/post migration, while Liquibase folds
     * the post-cutover branch — guard and all — into the pre-cutover changeset, where the two {@code sqlCheck}s are
     * ANDed and neither branch ever runs.
     */
    private static final Pattern MALFORMED_CHANGESET = Pattern
            .compile("(?im)^\\s*--\\s*changeset(?:\\s*$|\\s+(?![^\\s:]+:[^\\s]).*$)");

    /** How a failure message names this family's tables, e.g. "a trace table". */
    private final String familyNoun;

    /** The live table; {@code <live>_local} and {@code <live>_local_v2} complete the mutable set. */
    private final String liveTable;

    /** The shard whose existence the guard's {@code sqlCheck} must interrogate. */
    private final String shardTable;

    /**
     * The last migration that runs before an install of this family can have cut over. Everything after it must
     * tolerate both topologies; everything up to and including it ran pre-cutover only.
     *
     * <p>Declared here rather than in the tests so the lint's coverage boundary and the post-cutover gate's splice
     * point are one value: they are the same fact, and two copies of it could drift apart silently.
     */
    private final String cutoverSplicePoint;

    /**
     * A statement mutating one of this family's tables. Reads are excluded deliberately — {@code SELECT ... FROM spans}
     * is correct on both topologies.
     * <p>
     * The {@code _local_v2} shadow is included, which is not obvious: it looks single-topology, but the cutover
     * <i>renames it away</i>, so an unguarded shadow {@code ALTER} added after the splice point runs against a table
     * that no longer exists on any cut-over install and fails the migration outright. It needs the same branch
     * treatment as the others.
     */
    private final Pattern mutation;

    /** The subset of {@link #mutation} that no migration may use on these tables at all; see {@link #DESTRUCTIVE_KIND}. */
    private final Pattern destructiveMutation;

    /**
     * The topology check itself. Group 1 is the expected result, which is what distinguishes the pre-cutover branch
     * ({@code 0} — no shard) from the post-cutover one ({@code 1}).
     */
    private final Pattern topologyCheck;

    CutoverMigrationPreconditionLint(String familyNoun, String liveTable, String shardTable,
            String cutoverSplicePoint) {
        this.familyNoun = familyNoun;
        this.liveTable = liveTable;
        this.shardTable = shardTable;
        this.cutoverSplicePoint = cutoverSplicePoint;
        var familyTables = "%s|%s|%s_v2".formatted(liveTable, shardTable, shardTable);

        // An INSERT is topology-sensitive only when it names a table that exists on one side and not the other. Into
        // the live name it is correct on both — the Distributed wrapper accepts inserts and routes them to the shard —
        // so requiring a guard there would be ceremony. Into the shadow or the shard it fails outright on the wrong
        // topology, which is the same reason an unguarded shadow ALTER is caught below.
        var singleTopologyTables = "%s|%s_v2".formatted(shardTable, shardTable);

        this.mutation = Pattern.compile("(?im)^\\s*(?:"
                + statement(ANY_MUTATION_KIND, familyTables) + "|"
                + statement("INSERT\\s+INTO", singleTopologyTables) + ")");
        this.destructiveMutation = Pattern.compile("(?im)^\\s*" + statement(DESTRUCTIVE_KIND, familyTables));
        this.topologyCheck = Pattern.compile(
                "(?im)^\\s*--\\s*precondition-sql-check\\s+expectedResult:(\\d+)\\b"
                        // The check must actually interrogate the topology. Requiring only a number and the shard
                        // name somewhere on the line would accept a constant such as `SELECT 0 -- spans_local`,
                        // which evaluates the same on both topologies and so guards nothing.
                        + ".*\\bsystem\\.tables\\b"
                        // And it must name THIS family's shard: the two families cut over independently, so a guard
                        // keyed on the other one selects its branch from an unrelated fact.
                        + ".*\\bname\\s*=\\s*'%s'.*$".formatted(shardTable));
    }

    /**
     * Problems found in {@code sql}, empty when the migration is either topology-aware or irrelevant to this family's
     * tables. Messages name the file and changeset so a failure points at the edit that caused it.
     */
    List<String> problems(String fileName, String sql) {
        var problems = new ArrayList<String>();
        var guardedBranches = new LinkedHashSet<String>();
        var branchCounts = new LinkedHashMap<String, Integer>();
        var branchObjects = new LinkedHashMap<String, Set<String>>();
        boolean mutatesFamilyTable = false;

        var changeSets = changeSets(sql);

        if (MALFORMED_CHANGESET.matcher(sql).find()) {
            return List.of("""
                    %s: contains a `--changeset` line that does not name an author:id, so Liquibase would fold the \
                    statements under it into the previous changeset — where they would inherit a guard that was never \
                    written for them\
                    """.formatted(fileName));
        }

        // "Parsed nothing" must never read as "nothing wrong". If the file mutates one of these tables but no changeset
        // header was recognised, the checks below would all be skipped and the migration would pass unexamined.
        if (changeSets.isEmpty() && mutation.matcher(stripComments(sql)).find()) {
            return List.of("""
                    %s: mutates a %s table but no `--changeset` header could be parsed, so it cannot be checked — fix \
                    the header rather than leaving the migration unguarded\
                    """.formatted(fileName, familyNoun));
        }

        for (var changeSet : changeSets) {
            var statements = stripComments(changeSet.body());
            if (!mutation.matcher(statements).find()) {
                continue;
            }
            mutatesFamilyTable = true;

            var otherFamily = otherFamilyMutating(statements);
            if (otherFamily != null) {
                problems.add("""
                        %s: changeset '%s' mutates both %s and %s tables, which cannot be guarded correctly as one \
                        changeset — the families cut over independently, so a correct guard would need all four \
                        topology combinations, and a changeset carrying one sqlCheck per family gets them ANDed and \
                        goes MARK_RAN on any install where the two families differ. Split it into a guarded pre/post \
                        pair per family, each keyed on its own shard.\
                        """.formatted(fileName, changeSet.name(), familyNoun, otherFamily.familyNoun));
                continue;
            }

            if (destructiveMutation.matcher(statements).find()) {
                problems.add("""
                        %s: changeset '%s' drops, renames, truncates, exchanges, optimizes or deletes from a %s table. \
                        No migration may do that during the mixed-fleet window, guarded or not — post-cutover the \
                        Distributed wrapper rejects row mutations outright, and a structural change rides the \
                        successor's table definition rather than an in-window ALTER. If you believe you need one, that \
                        is a design conversation; see docs/cutover-table-schema-ddl.md.\
                        """.formatted(fileName, changeSet.name(), familyNoun));
                continue;
            }

            var check = topologyCheck.matcher(changeSet.body());
            boolean guarded = MARK_RAN.matcher(changeSet.body()).find()
                    && ON_ERROR_HALT.matcher(changeSet.body()).find()
                    && check.find();
            if (guarded) {
                guardedBranches.add(check.group(1));
                branchCounts.merge(check.group(1), 1, Integer::sum);
                branchObjects.computeIfAbsent(check.group(1), branch -> new LinkedHashSet<>())
                        .addAll(mutatedObjectsIn(statements));
            } else {
                problems.add("""
                        %s: changeset '%s' mutates a %s table without a complete topology guard — it needs \
                        `--preconditions onFail:MARK_RAN onError:HALT` and a `--precondition-sql-check \
                        expectedResult:N ... %s` on the changeset itself\
                        """.formatted(fileName, changeSet.name(), familyNoun, shardTable));
            }
        }

        if (mutatesFamilyTable && problems.isEmpty() && !guardedBranches.containsAll(REQUIRED_BRANCHES)) {
            problems.add("""
                    %s: a %s-table migration must ship BOTH complementary branches (expectedResult:0 for pre-cutover \
                    and expectedResult:1 for post-cutover); found only %s, so one topology would be recorded MARK_RAN \
                    and never receive the change\
                    """.formatted(fileName, familyNoun, guardedBranches));
        }

        // Branches must come in pairs. Counting rather than set-testing catches the file that guards two mutations to
        // the same topology and one to the other: the SET is still {0, 1}, so it looks complementary, while one
        // topology in fact receives a change the other never does.
        if (mutatesFamilyTable && problems.isEmpty()
                && !branchCounts.getOrDefault("0", 0).equals(branchCounts.getOrDefault("1", 0))) {
            problems.add("""
                    %s: the guarded branches must pair up — found %s pre-cutover (expectedResult:0) and %s \
                    post-cutover (expectedResult:1) mutating changesets, so at least one change reaches only one \
                    topology\
                    """.formatted(fileName, branchCounts.getOrDefault("0", 0), branchCounts.getOrDefault("1", 0)));
        }

        if (mutatesFamilyTable && problems.isEmpty()) {
            assertBranchesTouchTheSameObjects(fileName, branchObjects, problems);
        }

        return problems;
    }

    /**
     * The two branches must name the same columns, indices and projections. Which <i>tables</i> they target differs by
     * design — that is the whole point of branching — but the objects being added, dropped or modified are the change
     * itself, and both topologies must receive it.
     *
     * <p>Nothing else covers this, and it is not the structural check it looks like. Each parity gate applies a
     * migration to <b>one</b> topology: the pre-cutover gate never has a shard, so it only ever runs the
     * {@code expectedResult:0} branch, and the post-cutover gate splices the cutover in before the migration, so it
     * only ever runs the other. Neither ever sees both outcomes, so a file adding {@code idx_a} pre-cutover and
     * {@code idx_b} post-cutover satisfies every gate while leaving the fleet permanently split: an install that
     * migrated before cutting over carries {@code idx_a} on its shard, one that cut over first carries {@code idx_b}.
     *
     * <p>Comparing identifiers rather than statements keeps it free of false positives. The legitimate asymmetries are
     * all about placement — a storage-only change reaches both tables pre-cutover but the shard alone after it, and a
     * {@code MATERIALIZE} may be worth doing on one side only — and none of them change which object is named.
     */
    private void assertBranchesTouchTheSameObjects(String fileName, Map<String, Set<String>> branchObjects,
            List<String> problems) {
        var preCutover = branchObjects.getOrDefault("0", Set.of());
        var postCutover = branchObjects.getOrDefault("1", Set.of());
        if (preCutover.equals(postCutover)) {
            return;
        }

        problems.add(
                """
                        %s: the guarded branches must apply the same change — the pre-cutover branch names %s and the \
                        post-cutover branch names %s. Which tables each targets differs by design, but the columns, indices and \
                        projections must not: an install that migrated before cutting over would end up with a different schema \
                        from one that cut over first, and no parity gate can see it because each only ever runs one branch.\
                        """
                        .formatted(fileName, preCutover, postCutover));
    }

    /** Every column, index and projection {@code statements} names, lower-cased so casing cannot split a pair. */
    private Set<String> mutatedObjectsIn(String statements) {
        var objects = new LinkedHashSet<String>();
        var matcher = MUTATED_OBJECT.matcher(statements);
        while (matcher.find()) {
            objects.add(matcher.group(1).toLowerCase());
        }
        return objects;
    }

    /**
     * Another cutover family whose tables {@code statements} also mutate, or {@code null} when the statements stay
     * within this family.
     *
     * <p>Combined migrations across both families are an established shape here ({@code 000008}, {@code 000055} and
     * {@code 000084} all alter {@code traces} and {@code spans} in a single changeset), and they predate the cutover,
     * so they were correct when written. After it they cannot be: the families cut over independently, so one changeset
     * would have to cover four topology combinations rather than two. Carrying one {@code sqlCheck} per family does not
     * achieve that — Liquibase ANDs multiple preconditions, so both branches evaluate false on any install where the
     * families differ, and both are recorded {@code MARK_RAN} while the ledger claims the change was applied.
     *
     * <p>Without this check the guard would actively mislead: the single-family message above tells an author to add a
     * {@code sqlCheck} "on the changeset itself", which for a combined migration produces exactly that broken shape and
     * then passes both families' lints.
     */
    private CutoverMigrationPreconditionLint otherFamilyMutating(String statements) {
        for (var family : values()) {
            if (family != this && family.mutation.matcher(statements).find()) {
                return family;
            }
        }
        return null;
    }

    /**
     * The file split into changesets. Anything before the first {@code --changeset} is the file header and is dropped:
     * header prose cannot guard a mutation, and including it is exactly how a file-level search gets fooled.
     */
    private List<ChangeSet> changeSets(String sql) {
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
            changeSets.add(ChangeSet.builder()
                    .name(names.get(i))
                    .body(sql.substring(headerEnds.get(i), bodyEnd))
                    .build());
        }
        return changeSets;
    }

    /**
     * Removes both comment forms before looking for mutations: {@code --} to end of line, and {@code /* ... *}{@code /}
     * blocks. Stripping line comments alone leaves a block-commented mutation looking real, which rejects a perfectly
     * valid migration — a false positive, and the kind that teaches people to distrust the lint. Precondition
     * directives are matched against the raw text instead, since they <i>are</i> line comments.
     */
    private String stripComments(String sql) {
        return stripLineComments(BLOCK_COMMENT.matcher(sql).replaceAll(" "));
    }

    private String stripLineComments(String sql) {
        return sql.lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment < 0 ? line : line.substring(0, comment);
                })
                .collect(Collectors.joining("\n"));
    }

    @Builder(toBuilder = true)
    private record ChangeSet(String name, String body) {
    }
}
