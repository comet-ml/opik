package com.comet.opik.domain;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ParseContext;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The post-run check on a free-form SQL query (OPIK-8566): its results are returned only if every table of the
 * database that it read can be shown to have been read under a row policy. No single ClickHouse source shows every
 * read, so three are combined:
 * <ul>
 * <li>{@code system.query_log}, one entry for the initiator and one per shard-side read. It records the policies
 * applied to the tables read at each entry's top level, which covers every shard-side read of a local table behind
 * a Distributed one. Each entry is checked on its own, so a shard that skipped a policy another applied is caught.</li>
 * <li>{@code EXPLAIN json = 1, actions = 1}: every read the initiator plans, CTEs, derived tables and UNIONs
 * included, carries a {@code Row level filter} where a policy applied. A visible read without one fails.</li>
 * <li>Reads evaluated while the query is analysed: {@code IN}, {@code EXISTS} and scalar subqueries. They are in
 * the log's tables but neither source shows their policy. {@code IN} and {@code EXISTS} only filter the outer rows,
 * so such a read is accepted unless it is in a scalar subquery ({@link FreeFormSqlSubqueries}), whose value reaches
 * the result. Scalar subqueries reading a table are rejected before running.</li>
 * </ul>
 * Every planned read must be a {@code ReadFromMergeTree} with its row filter: the read-only profile pins off the
 * count shortcuts ({@code optimize_trivial_count_query}, {@code optimize_use_implicit_projections}) that answer from
 * metadata or the index and leave no read to check, so a {@code ReadFromPreparedSource} or any other read fails.
 * It also pins {@code prefer_localhost_replica = 1}, so a single-shard Distributed read runs on the initiator and
 * every read of it, nested ones included, is in the plan. Shard entries exist only for remote shards, and there a
 * read the shard query nests is in neither source, so it fails closed.
 * A Distributed wrapper ({@code traces}) counts as covered when its local table's policy was applied: it reads
 * nothing itself. Tables outside the database ({@code system.one}, {@code numbers}) carry no policy by design.
 */
@UtilityClass
class FreeFormSqlPolicyCheck {

    private static final ParseContext JSON = JsonPath.using(Configuration.builder()
            .options(Option.DEFAULT_PATH_LEAF_TO_NULL, Option.SUPPRESS_EXCEPTIONS)
            .build());
    private static final JsonPath READS = JsonPath.compile("$..[?(@['Node Type'] =~ /ReadFrom.*/)]");
    /** Reads with nothing to check here: row generators, and remote reads, whose shard entries the log covers. */
    private static final Set<String> UNCHECKED_READS = Set.of("ReadFromSystemOne", "ReadFromSystemNumbers",
            "ReadFromSystemZeros", "ReadFromRemote", "ReadFromRemoteParallelReplicas");

    /**
     * One {@code query_log} entry: the {@code <db>.<table>} names it read, and those its applied row policies cover,
     * resolved through {@code system.row_policies}.
     */
    @Builder
    record LogEntry(boolean initial, @NonNull String user, @NonNull List<String> tables,
            @NonNull List<String> policedTables) {
    }

    /** A table read that could not be shown to be under a row policy, and why. */
    record Violation(String table, String reason) {
    }

    /** @return the first read that cannot be shown to have run under its row policy; empty when there is none. */
    static Optional<Violation> violation(@NonNull String database, @NonNull String user,
            @NonNull List<LogEntry> entries,
            @NonNull String planJson, @NonNull Set<String> scalarReads) {
        if (entries.stream().noneMatch(entry -> entry.initial() && entry.user().equals(user))) {
            return Optional.of(new Violation("", "no query log entry for the query as " + user));
        }
        String prefix = database + ".";
        var plannedReads = new HashSet<String>();
        var filteredReads = new HashSet<String>();
        List<Map<String, Object>> reads = JSON.parse(planJson).read(READS);
        for (var read : reads) {
            String type = String.valueOf(read.get("Node Type"));
            String description = String.valueOf(read.getOrDefault("Description", ""));
            if (UNCHECKED_READS.contains(type)) {
                continue;
            }
            if (!type.equals("ReadFromMergeTree")) {
                return Optional.of(new Violation(description, "unverifiable %s read".formatted(type)));
            }
            if (!description.startsWith(prefix)) {
                continue;
            }
            plannedReads.add(description);
            if (!(read.get("Prewhere info") instanceof Map<?, ?> prewhere
                    && prewhere.get("Row level filter") != null)) {
                return Optional.of(new Violation(description, "read without a row policy in the plan"));
            }
            filteredReads.add(description);
        }
        Set<String> policedAnywhere = entries.stream()
                .flatMap(entry -> entry.policedTables().stream())
                .collect(Collectors.toSet());
        for (var entry : entries) {
            Set<String> policed = Set.copyOf(entry.policedTables());
            for (String table : entry.tables()) {
                if (!table.startsWith(prefix) || policed.contains(table)
                        || policedAnywhere.contains(table + "_local")) {
                    continue;
                }
                // The initiator's entry has no policies for its nested reads: a planned one shows its filter in the
                // plan, and one absent from the plan was evaluated during analysis, accepted unless scalar.
                boolean shownElsewhere = entry.initial() && (filteredReads.contains(table)
                        || (!plannedReads.contains(table) && !scalarReads.contains(table)));
                if (!shownElsewhere) {
                    return Optional.of(new Violation(table,
                            entry.initial() ? "read without a row policy" : "read without a row policy on a shard"));
                }
            }
        }
        return Optional.empty();
    }
}
