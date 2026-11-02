package com.comet.opik.domain;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ParseContext;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The post-run check's rule (OPIK-8566): whether every table of the database that a free-form SQL query read can be
 * shown to have been read under a row policy. It only decides; what follows from a violation depends on the mode
 * ({@link FreeFormSqlQueryService}): reported in audit, results withheld in enforce. No single ClickHouse source
 * shows every read, so three are combined:
 * <ul>
 * <li>{@code system.query_log}, one entry for the initiator and one per shard-side read. It records the policies
 * applied to the tables read at each entry's top level, which covers every shard-side read of a local table behind
 * a Distributed one. Each entry is checked on its own, so a shard that skipped a policy another applied is caught.</li>
 * <li>{@code EXPLAIN json = 1, actions = 1}: every read the initiator plans, CTEs, derived tables and UNIONs
 * included, carries a {@code Row level filter} where a policy applied. A visible read without one fails.</li>
 * <li>Reads evaluated while the query is analysed: {@code IN}, {@code EXISTS} and scalar subqueries. They are in
 * the log's tables but neither source shows their policy. {@code IN} and {@code EXISTS} only filter the outer rows,
 * so such a read is accepted when the resolved query tree shows it under one of them and in no scalar subquery
 * ({@link FreeFormSqlSubqueries}), whose value would reach the result. Any other read missing from the plan fails.</li>
 * </ul>
 * Every planned read must be a {@code ReadFromMergeTree} with its row filter: the read-only profile pins off the
 * count shortcuts ({@code optimize_trivial_count_query}, {@code optimize_use_implicit_projections}) that answer from
 * metadata or the index and leave no read to check, so a {@code ReadFromPreparedSource} or any other read fails.
 * It also pins {@code prefer_localhost_replica = 1}, so a single-shard Distributed read runs on the initiator, its
 * local table in the plan or, under {@code IN} or {@code EXISTS}, in the log's tables. Shard entries exist only for
 * remote shards, and there a read the shard query nests is in neither source, so it fails closed.
 * A Distributed wrapper ({@code traces}) on the initiator's entry counts as covered when its local table's policy was
 * applied: it reads nothing itself, and every entry reading the local table is checked on its own. Tables outside the database ({@code system.one}, {@code numbers}) carry no policy by design.
 */
@UtilityClass
class FreeFormSqlPolicyCheck {

    private static final ParseContext JSON_PATH_CONTEXT = JsonPath.using(Configuration.builder()
            .options(Option.DEFAULT_PATH_LEAF_TO_NULL, Option.SUPPRESS_EXCEPTIONS)
            .build());
    private static final JsonPath READS = JsonPath.compile("$..[?(@['Node Type'] =~ /ReadFrom.*/)]");
    /** Reads with nothing to check here: row generators, and remote reads, whose shard entries the log covers. */
    private static final Set<String> UNCHECKED_READS = Set.of("ReadFromSystemOne", "ReadFromSystemNumbers",
            "ReadFromSystemZeros", "ReadFromRemote", "ReadFromRemoteParallelReplicas");

    /** @return the first read that cannot be shown to have run under its row policy; empty when there is none. */
    static Optional<FreeFormSqlPolicyViolation> violation(@NonNull String database, @NonNull String user,
            @NonNull List<FreeFormSqlQueryLogEntry> entries,
            @NonNull String planJson, @NonNull FreeFormSqlSubqueries.SubqueryReads subqueryReads) {
        if (entries.stream().noneMatch(entry -> entry.initial() && entry.user().equals(user))) {
            return Optional.of(FreeFormSqlPolicyViolation.builder().table("")
                    .reason("no query log entry for the query as %s".formatted(user)).build());
        }
        String prefix = database + ".";
        var plannedReads = new HashSet<String>();
        var filteredReads = new HashSet<String>();
        List<Map<String, Object>> plannedReadNodes = JSON_PATH_CONTEXT.parse(planJson).read(READS);
        for (var read : plannedReadNodes) {
            String type = String.valueOf(read.get("Node Type"));
            String description = String.valueOf(read.getOrDefault("Description", ""));
            if (UNCHECKED_READS.contains(type)) {
                continue;
            }
            if (!type.equals("ReadFromMergeTree")) {
                return Optional.of(FreeFormSqlPolicyViolation.builder().table(description)
                        .reason("unverifiable %s read".formatted(type)).build());
            }
            if (!description.startsWith(prefix)) {
                continue;
            }
            plannedReads.add(description);
            if (!(read.get("Prewhere info") instanceof Map<?, ?> prewhere
                    && prewhere.get("Row level filter") != null)) {
                return Optional.of(FreeFormSqlPolicyViolation.builder().table(description)
                        .reason("read without a row policy in the plan").build());
            }
            filteredReads.add(description);
        }
        Set<String> policyCoveredAnywhere = entries.stream()
                .flatMap(entry -> entry.policyCoveredTables().stream())
                .collect(Collectors.toSet());
        for (var entry : entries) {
            Set<String> policed = Set.copyOf(entry.policyCoveredTables());
            for (String table : entry.tables()) {
                // A Distributed wrapper on the initiator reads nothing itself: its local table's read carries it,
                // covered by a logged policy or by a row filter in the plan, and each local read is checked on its own.
                String local = table + "_local";
                if (!table.startsWith(prefix) || policed.contains(table) || (entry.initial()
                        && (policyCoveredAnywhere.contains(local) || filteredReads.contains(local)))) {
                    continue;
                }
                // The initiator's entry has no policies for its nested reads: a planned one shows its filter in the
                // plan, and one absent from the plan must be shown by the query tree to sit under IN or EXISTS. The
                // tree names a local table read in-process by its Distributed wrapper (traces_local as traces), and
                // a scalar read under either name excludes it.
                String distributedTable = StringUtils.removeEnd(table, "_local");
                boolean underFilter = subqueryReads.filter().contains(table)
                        || subqueryReads.filter().contains(distributedTable);
                boolean underScalar = subqueryReads.scalar().contains(table)
                        || subqueryReads.scalar().contains(distributedTable);
                boolean shownElsewhere = entry.initial() && (filteredReads.contains(table)
                        || (!plannedReads.contains(table) && underFilter && !underScalar));
                if (!shownElsewhere) {
                    return Optional.of(FreeFormSqlPolicyViolation.builder().table(table).reason(
                            entry.initial() ? "read without a row policy" : "read without a row policy on a shard")
                            .build());
                }
            }
        }
        return Optional.empty();
    }
}
