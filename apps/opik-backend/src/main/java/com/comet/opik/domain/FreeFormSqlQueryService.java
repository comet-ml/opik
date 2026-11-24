package com.comet.opik.domain;

import com.clickhouse.client.api.ServerException;
import com.clickhouse.client.api.metadata.NoSuchColumnException;
import com.comet.opik.api.AnalyticsQueryResponse;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.DatabaseAnalyticsReadOnlyFreeFormSqlConfig;
import com.comet.opik.infrastructure.FreeFormSqlPostRunCheckConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Throwables;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Orchestrates caller-supplied, read-only free-form SQL bounded to a single workspace/project. First consumer is the
 * Agent Insights subagent, but the service is intentionally feature-agnostic.
 *
 * <p>Every query is pre-flighted through {@code EXPLAIN AST} (see {@link FreeFormSqlQueryDAO}). The service rejects
 * unparseable queries, {@code Set} nodes for SETTINGS/SET clauses at any depth, catalog references, table functions,
 * forbidden function calls, and names ClickHouse resolves to a forbidden call when written bare ({@code user}), also as
 * an alias or CTE name, before ClickHouse analyses the query. Every query that passes is then pre-flighted through
 * {@code EXPLAIN QUERY TREE} under its caller scope, as a backstop: the resolved tree restricts table reads to the
 * account's tables and rejects forbidden functions by their canonical name. The tree also rejects scalar subqueries
 * that read tables in every mode. The workspace and
 * project bounds are sent as ClickHouse server settings consumed by the restrictive row policies.
 *
 * <p>The post-run check verifies from the query's {@code system.query_log} entries and plan that every table it read
 * was read under a row policy ({@link FreeFormSqlPolicyCheck}). Its behavior depends on the configured mode:
 * <ul>
 * <li>{@code off}: the post-run check is skipped.</li>
 * <li>{@code audit}: the check reports its findings without withholding results.</li>
 * <li>{@code enforce}: results are returned only once the check passes, and are withheld on a violation,
 * a missing log entry or a failure.</li>
 * </ul>
 * Every outcome is counted in {@code opik.free_form_sql.post_run_check}, tagged by mode and outcome.
 */
@Slf4j
@Singleton
public class FreeFormSqlQueryService {

    public static final String METRIC_NAMESPACE = "opik.free_form_sql.queries";

    private static final AttributeKey<String> RESULT_KEY = stringKey("result");
    private static final AttributeKey<String> REASON_KEY = stringKey("reason");
    private static final AttributeKey<String> MODE_KEY = stringKey("mode");
    private static final AttributeKey<String> OUTCOME_KEY = stringKey("outcome");
    private static final AttributeKey<String> ACCOUNT_KEY = stringKey("account");

    /**
     * The terminal outcome of a query, carrying its {@code (result, reason)} metric tags. The duration histogram is
     * tagged with these and its per-tag count is the query counter, so no separate counters are needed.
     */
    private enum Outcome {
        SUCCESS("success", "none"),
        SETTINGS_CLAUSE("rejected", "settings_clause_rejected"),
        PARSE_ERROR("rejected", "parse_error"),
        PERMISSION_DENIED("error", "permission_denied"),
        FORBIDDEN_TABLE("rejected", "forbidden_table"),
        FORBIDDEN_FUNCTION("rejected", "forbidden_function"),
        SCALAR_SUBQUERY_NOT_ALLOWED("rejected", "scalar_subquery_not_allowed"),
        POLICY_UNVERIFIED("error", "policy_unverified"),
        POLICY_CHECK_FAILED("error", "policy_check_failed"),
        CH_LIMIT("error", "ch_limit"),
        CH_BUSY("error", "ch_busy"),
        OTHER("error", "other");

        private final Attributes attributes;

        Outcome(String result, String reason) {
            this.attributes = Attributes.of(RESULT_KEY, result, REASON_KEY, reason);
        }
    }

    /**
     * The parser models every SETTINGS/SET clause (top-level, subquery, CTE, {@code FORMAT ... SETTINGS}) as this AST
     * node. Verified against ClickHouse 25.3.x: {@code EXPLAIN AST} prints it as the leading token {@code "Set"}.
     * Matching the exact node token (not a {@code "Set"} prefix) avoids false positives on Set-prefixed identifiers and
     * keeps intent explicit; the SETTINGS rejection tests fail loudly if a future ClickHouse version renames the node.
     */
    private static final Set<String> SETTINGS_AST_NODES = Set.of("Set");

    private static final String TABLE_IDENTIFIER_AST_PREFIX = "TableIdentifier ";
    private static final String IDENTIFIER_AST_PREFIX = "Identifier ";
    private static final Pattern AST_CHILDREN_SUFFIX = Pattern.compile(" \\(children \\d+\\)$");
    private static final Pattern AST_ALIAS_SUFFIX = Pattern.compile(" \\(alias (.+)\\)$");
    private static final String TABLE_EXPRESSION_AST_PREFIX = "TableExpression ";
    /**
     * ClickHouse's own metadata schemas, which the free-form account must never read: the row policies bound tenant
     * data, but these schemas sit outside them and would disclose the server's tables, columns, settings and ACLs.
     * Matched on the database qualifier of a {@code TableIdentifier} or {@code Identifier} node, lower-cased, since
     * ClickHouse exposes both {@code information_schema} and {@code INFORMATION_SCHEMA} and resolves a qualified column
     * such as {@code system.one.dummy} without a FROM clause. ClickHouse grants deny most of these too; direct
     * table references are rejected in the app even if those grants change.
     */
    private static final Set<String> FORBIDDEN_CATALOG_PREFIXES = Set.of("system.", "information_schema.");
    /** Logical tables exposed to each account. Keep in sync with the read-only grants and row policies. */
    private static final Map<FreeFormSqlAccount, Set<String>> ALLOWED_TABLES = Map.of(
            FreeFormSqlAccount.STANDARD, Set.of("spans", "traces", "authored_feedback_scores"),
            FreeFormSqlAccount.EXTENDED, Set.of("spans", "traces", "authored_feedback_scores", "feedback_scores",
                    "trace_threads", "experiments", "experiment_items", "dataset_items", "dataset_item_versions"));

    private static final String FUNCTION_AST_PREFIX = "Function ";
    public static final String PARSE_ERROR_MESSAGE = "Query rejected: could not be parsed";
    public static final String SETTINGS_CLAUSE_MESSAGE = "Query rejected: SETTINGS/SET clauses are not allowed";
    public static final String ACCESS_DENIED_MESSAGE = "Query rejected: access denied";
    public static final String RESULT_SHAPE_MESSAGE = "Query must return exactly one column named 'result'";
    public static final String INVALID_JSON_RESULT_MESSAGE = "Query 'result' column must contain valid JSON produced via toJSONString(...)";
    public static final String EXECUTION_FAILED_MESSAGE = "Query execution failed";
    /** Returned to the caller, an LLM agent, so it says what to change and how. */
    static final String SCALAR_SUBQUERY_MESSAGE = """
            Query rejected: a scalar subquery (a subquery used as a value, e.g. SELECT (SELECT count() FROM spans)) \
            cannot read a table; this one reads %s. Compute the value in a CTE and select from it instead, e.g. \
            WITH s AS (SELECT count() AS n FROM spans) SELECT toJSONString(map('n', toString(s.n))) AS result FROM s, \
            or join it to the main query. Subqueries under IN and EXISTS are allowed.""";

    static final String LIMIT_MESSAGE = "Query exceeded ClickHouse limits";
    static final String BYTES_LIMIT_MESSAGE = """
            Query exceeded ClickHouse limits: it read too many bytes. Narrow the start_time range, or drop wide \
            columns (input, output, metadata) from the query.""";

    /** ClickHouse error codes surfaced to the caller as a clean 4xx rather than a 500. */
    private static final int CH_TOO_MANY_ROWS = 158;
    private static final int CH_TIMEOUT_EXCEEDED = 159;
    private static final int CH_MEMORY_LIMIT_EXCEEDED = 241;
    private static final int CH_TOO_MANY_ROWS_OR_BYTES = 396;
    private static final int CH_TOO_MANY_BYTES = 307;
    private static final int CH_ACCESS_DENIED = 497;
    private static final int CH_SYNTAX_ERROR = 62;
    /** The account's concurrent-query cap, e.g. a dashboard loading many widgets at once: worth a retry. */
    private static final int CH_TOO_MANY_SIMULTANEOUS_QUERIES = 202;
    private static final String RETRY_AFTER_SECONDS = "1";
    private static final Set<Integer> CH_LIMIT_CODES = Set.of(
            CH_TOO_MANY_ROWS, CH_TIMEOUT_EXCEEDED, CH_MEMORY_LIMIT_EXCEEDED, CH_TOO_MANY_ROWS_OR_BYTES,
            CH_TOO_MANY_BYTES);

    private final FreeFormSqlQueryDAO freeFormSqlQueryDAO;
    private final FreeFormSqlEntityNameEnricher entityNameEnricher;
    private final FreeFormSqlQueryLogReader queryLogReader;
    private final FreeFormSqlPostRunCheckConfig.Mode mode;
    private final String database;
    private final Map<FreeFormSqlAccount, String> users;

    private final LongHistogram duration;
    private final LongHistogram resultRows;
    private final LongHistogram bytesRead;
    private final LongCounter checks;

    @Inject
    public FreeFormSqlQueryService(@NonNull FreeFormSqlQueryDAO freeFormSqlQueryDAO,
            @NonNull FreeFormSqlEntityNameEnricher entityNameEnricher,
            @NonNull FreeFormSqlQueryLogReader queryLogReader,
            @NonNull @Config("freeFormSqlPostRunCheck") FreeFormSqlPostRunCheckConfig postRunCheck,
            @NonNull @Config("databaseAnalytics") DatabaseAnalyticsFactory databaseAnalytics,
            @NonNull @Config("databaseAnalyticsReadOnlyFreeFormSql") DatabaseAnalyticsReadOnlyFreeFormSqlConfig standard,
            @NonNull @Config("databaseAnalyticsReadOnlyFreeFormExtendedSql") DatabaseAnalyticsReadOnlyFreeFormSqlConfig extended) {
        this.freeFormSqlQueryDAO = freeFormSqlQueryDAO;
        this.entityNameEnricher = entityNameEnricher;
        this.queryLogReader = queryLogReader;
        this.mode = postRunCheck.getMode();
        this.database = databaseAnalytics.getDatabaseName();
        this.users = Map.of(FreeFormSqlAccount.STANDARD, standard.getUsername(),
                FreeFormSqlAccount.EXTENDED, extended.getUsername());

        var meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.duration = meter
                .histogramBuilder("%s.duration".formatted(METRIC_NAMESPACE))
                .setDescription(
                        "Duration of a single free-form SQL query, tagged by result and reason; its count is also the query counter")
                .setUnit("ms")
                .ofLongs()
                .build();
        this.resultRows = meter
                .histogramBuilder("%s.result_rows".formatted(METRIC_NAMESPACE))
                .setDescription("Number of rows returned by a successful free-form SQL query")
                .ofLongs()
                .build();
        this.bytesRead = meter
                .histogramBuilder("%s.bytes_read".formatted(METRIC_NAMESPACE))
                .setDescription("Number of bytes read by a successful free-form SQL query")
                .ofLongs()
                .build();
        this.checks = meter
                .counterBuilder("opik.free_form_sql.post_run_check")
                .setDescription("Free-form SQL post-run check and scalar subquery outcomes, tagged by mode and outcome")
                .build();
    }

    /** What the post-run check or the scalar subquery gate found, as its {@code outcome} metric tag. */
    @RequiredArgsConstructor
    private enum CheckOutcome {
        VERIFIED("verified"),
        VIOLATION("violation"),
        LOG_MISSING("log_missing"),
        CHECK_FAILED("check_failed"),
        SCALAR_READ("scalar_read");

        private final String tag;
    }

    private void count(CheckOutcome outcome, FreeFormSqlAccount account) {
        checks.add(1, Attributes.of(MODE_KEY, mode.name().toLowerCase(), OUTCOME_KEY, outcome.tag, ACCOUNT_KEY,
                account.name().toLowerCase()));
    }

    /**
     * Runs {@code query} for {@code account}, bounded to {@code workspaceId}. A null {@code projectId} means every
     * project in that workspace; callers pass the id they have rather than the sentinel the row policies read,
     * which stays inside this layer.
     */
    public CompletableFuture<AnalyticsQueryResponse> executeQuery(@NonNull FreeFormSqlAccount account,
            @NonNull String workspaceId, @Nullable UUID projectId, @NonNull String query) {
        long startMillis = System.currentTimeMillis();
        String projectScope = projectId == null ? FreeFormSqlQueryDAO.PROJECT_ID_ALL : projectId.toString();

        return freeFormSqlQueryDAO.explainAst(account, query)
                .handle((nodeLabels, error) -> validateAst(nodeLabels, error, startMillis))
                .thenCompose(ignored -> resolvedMetadataCheck(account, workspaceId, projectScope, query, startMillis))
                .thenCompose(queryTree -> {
                    var reads = rejectScalarReads(account, queryTree, startMillis);
                    return runQuery(account, workspaceId, projectScope, query, reads, startMillis);
                });
    }

    /** Resolve table reads and function calls before running the query. */
    private CompletableFuture<List<String>> resolvedMetadataCheck(FreeFormSqlAccount account, String workspaceId,
            String projectScope, String query, long startMillis) {
        return freeFormSqlQueryDAO.explainQueryTree(account, workspaceId, projectScope, query)
                .handle((queryTree, error) -> {
                    if (error != null) {
                        throw mapExecutionError(error, startMillis);
                    }
                    try {
                        if (containsForbiddenResolvedTable(account, queryTree)) {
                            throw reject(Outcome.FORBIDDEN_TABLE, startMillis, ACCESS_DENIED_MESSAGE, null);
                        }
                        if (containsForbiddenOrUnreadableFunction(queryTree)) {
                            throw reject(Outcome.FORBIDDEN_FUNCTION, startMillis, ACCESS_DENIED_MESSAGE, null);
                        }
                    } catch (WebApplicationException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        throw mapExecutionError(e, startMillis);
                    }
                    return queryTree;
                });
    }

    /**
     * A scalar subquery reading a table cannot be shown to have run under its row policy (see
     * {@link FreeFormSqlPolicyCheck}), so it is rejected before running, with the rewrite the caller can apply.
     */
    private FreeFormSqlSubqueries.SubqueryReads rejectScalarReads(FreeFormSqlAccount account, List<String> queryTree,
            long startMillis) {
        var subqueryReads = FreeFormSqlSubqueries.subqueryReads(queryTree, database);
        if (subqueryReads.hasScalar()) {
            count(CheckOutcome.SCALAR_READ, account);
            throw reject(Outcome.SCALAR_SUBQUERY_NOT_ALLOWED, startMillis,
                    SCALAR_SUBQUERY_MESSAGE.formatted(describe(subqueryReads)), null);
        }
        return subqueryReads;
    }

    /** The scalar reads by table name, as the caller would write them. */
    private String describe(FreeFormSqlSubqueries.SubqueryReads subqueryReads) {
        var reads = subqueryReads.scalar().stream().map(table -> table.substring(database.length() + 1)).sorted()
                .collect(Collectors.toCollection(ArrayList::new));
        if (subqueryReads.opaque()) {
            reads.add("a subquery whose result is too large to inspect");
        }
        return String.join(", ", reads);
    }

    /**
     * A failed EXPLAIN AST is a hard reject: never fall through to execution. A genuine syntax error (unparseable
     * input, statement-stacking) is reported as parse_error; any other failure (transport, permissions, ...) is routed
     * through the standard execution-error mapping so it isn't mislabelled. A successful parse carrying a SETTINGS/SET
     * clause is rejected before execution.
     */
    private List<String> validateAst(List<String> nodeLabels, Throwable error, long startMillis) {
        if (error != null) {
            throw isSyntaxError(error)
                    ? reject(Outcome.PARSE_ERROR, startMillis, PARSE_ERROR_MESSAGE, error)
                    : mapExecutionError(error, startMillis);
        }
        if (containsSetNode(nodeLabels)) {
            throw reject(Outcome.SETTINGS_CLAUSE, startMillis, SETTINGS_CLAUSE_MESSAGE, null);
        }
        if (containsCatalogReference(nodeLabels) || containsTableFunctionNode(nodeLabels)) {
            throw reject(Outcome.FORBIDDEN_TABLE, startMillis, ACCESS_DENIED_MESSAGE, null);
        }
        if (containsForbiddenName(nodeLabels)) {
            // Same generic message as a denied table, so the rejection does not reveal which guard tripped.
            throw reject(Outcome.FORBIDDEN_FUNCTION, startMillis, ACCESS_DENIED_MESSAGE, null);
        }
        return nodeLabels;
    }

    private CompletableFuture<AnalyticsQueryResponse> runQuery(FreeFormSqlAccount account, String workspaceId,
            String projectScope, String query, FreeFormSqlSubqueries.SubqueryReads subqueryReads,
            long startMillis) {
        String queryId = UUID.randomUUID().toString();
        return freeFormSqlQueryDAO.execute(account, workspaceId, projectScope, query, queryId)
                .handle((result, error) -> {
                    if (error != null) {
                        throw mapExecutionError(error, startMillis);
                    }
                    return result;
                })
                .thenCompose(result -> switch (mode) {
                    case OFF -> CompletableFuture.completedFuture(result);
                    case AUDIT -> {
                        audit(account, workspaceId, projectScope, query, queryId, subqueryReads);
                        yield CompletableFuture.completedFuture(result);
                    }
                    case ENFORCE -> verifyPolicies(account, workspaceId, projectScope, query, queryId, subqueryReads,
                            startMillis).thenApply(verified -> result);
                })
                .thenCompose(result -> {
                    recordSuccess(result, startMillis);
                    return resolveEntityNames(account, result, workspaceId);
                });
    }

    /** What the post-run check found; a check that could not run completes exceptionally instead. */
    private CompletableFuture<CheckOutcome> check(FreeFormSqlAccount account, String workspaceId, String projectScope,
            String query, String queryId, FreeFormSqlSubqueries.SubqueryReads subqueryReads) {
        String user = users.get(account);
        return freeFormSqlQueryDAO.explainPlan(account, workspaceId, projectScope, query)
                .thenCompose(plan -> queryLogReader.entries(queryId, user)
                        .thenApply(entries -> FreeFormSqlPolicyCheck.violation(database, user, entries, plan,
                                subqueryReads)))
                .thenApply(violation -> violation.map(found -> {
                    // Not the query's fault: a row policy did not apply where it should. Loud on purpose. The query
                    // text is left out: it is the caller's, and the id finds it in query_log.
                    log.error("Free-form SQL post-run policy check found '{}' for account '{}', query '{}': {} ({})",
                            found.missingLog() ? "log_missing" : "violation", account, queryId, found.table(),
                            found.reason());
                    return found.missingLog() ? CheckOutcome.LOG_MISSING : CheckOutcome.VIOLATION;
                }).orElse(CheckOutcome.VERIFIED));
    }

    /** Audit: runs the check and reports it; never throws, and never touches the request. */
    private void audit(FreeFormSqlAccount account, String workspaceId, String projectScope, String query,
            String queryId, FreeFormSqlSubqueries.SubqueryReads subqueryReads) {
        try {
            check(account, workspaceId, projectScope, query, queryId, subqueryReads)
                    .whenComplete((outcome, error) -> {
                        if (error != null) {
                            log.warn("Free-form SQL post-run policy check could not run for query '{}'", queryId,
                                    error);
                            count(CheckOutcome.CHECK_FAILED, account);
                        } else {
                            count(outcome, account);
                        }
                    });
        } catch (RuntimeException e) {
            log.warn("Free-form SQL post-run policy check could not start for query '{}'", queryId, e);
            count(CheckOutcome.CHECK_FAILED, account);
        }
    }

    /** Enforce: fails closed on a violation, a missing log entry and on any failure to run the check. */
    private CompletableFuture<Void> verifyPolicies(FreeFormSqlAccount account, String workspaceId, String projectScope,
            String query, String queryId, FreeFormSqlSubqueries.SubqueryReads subqueryReads, long startMillis) {
        return check(account, workspaceId, projectScope, query, queryId, subqueryReads).handle((outcome, error) -> {
            if (error != null) {
                count(CheckOutcome.CHECK_FAILED, account);
                // The check's own queries count against the account's concurrency cap, like the query did.
                if (isTooManySimultaneousQueries(error)) {
                    throw busy(startMillis, error);
                }
                log.error("Free-form SQL post-run policy check could not run for query '{}'", queryId, error);
                throw fail(Outcome.POLICY_CHECK_FAILED, startMillis, Response.Status.SERVICE_UNAVAILABLE,
                        "Query result withheld: its scope could not be verified");
            }
            count(outcome, account);
            if (outcome != CheckOutcome.VERIFIED) {
                throw fail(Outcome.POLICY_UNVERIFIED, startMillis, Response.Status.INTERNAL_SERVER_ERROR,
                        "Query result withheld: its scope could not be verified; this has been reported");
            }
            return null;
        });
    }

    /**
     * Resolves names by ids for datasets and projects.
     *
     * <p>Runs on {@code boundedElastic} rather than inline. The enclosing callback executes on a ClickHouse client
     * completion thread, and this step is a blocking MySQL round trip — leaving it there would let a slow lookup
     * hold a thread that other queries' completions are waiting on.
     */
    private CompletableFuture<AnalyticsQueryResponse> resolveEntityNames(FreeFormSqlAccount account,
            FreeFormSqlResult result, String workspaceId) {
        if (account != FreeFormSqlAccount.EXTENDED) {
            return CompletableFuture.completedFuture(toResponse(result.rows()));
        }
        return Mono.fromCallable(() -> toResponse(enrichOrKeepIds(result, workspaceId)))
                .subscribeOn(Schedulers.boundedElastic())
                .toFuture();
    }

    /** Enrichment is presentation: a failure leaves the ids in place rather than losing a result ClickHouse returned. */
    private List<JsonNode> enrichOrKeepIds(FreeFormSqlResult result, String workspaceId) {
        try {
            return entityNameEnricher.enrich(result.rows(), workspaceId);
        } catch (Exception e) {
            log.warn("Name enrichment failed for workspace '{}'; returning unresolved ids", workspaceId, e);
            return result.rows();
        }
    }

    private static AnalyticsQueryResponse toResponse(List<JsonNode> rows) {
        return AnalyticsQueryResponse.builder().results(rows).build();
    }

    private static boolean containsSetNode(List<String> nodeLabels) {
        return nodeLabels.stream()
                .map(FreeFormSqlQueryService::firstToken)
                .anyMatch(SETTINGS_AST_NODES::contains);
    }

    private static String firstToken(String label) {
        return label.stripLeading().split("\\s", 2)[0];
    }

    private static String firstTokenAfterPrefix(String label, String prefix) {
        return firstToken(label.substring(prefix.length()));
    }

    private static boolean containsCatalogReference(List<String> nodeLabels) {
        return nodeLabels.stream()
                .flatMap(label -> referencedName(label).stream())
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(name -> FORBIDDEN_CATALOG_PREFIXES.stream().anyMatch(name::startsWith));
    }

    /**
     * A forbidden function called by any spelling, or a name ClickHouse calls when written bare, wherever it appears:
     * an identifier, a table or CTE reference, or an alias.
     */
    private static boolean containsForbiddenName(List<String> nodeLabels) {
        return nodeLabels.stream().anyMatch(label -> isForbidden(functionName(label), FreeFormSqlForbiddenFunctions.ALL)
                || isForbidden(referencedName(label), FreeFormSqlForbiddenFunctions.BARE_NAMES)
                || isForbidden(alias(label), FreeFormSqlForbiddenFunctions.BARE_NAMES));
    }

    private static boolean isForbidden(Optional<String> name, Set<String> forbidden) {
        return name.map(value -> forbidden.contains(value.toLowerCase(Locale.ROOT))).orElse(false);
    }

    private static Optional<String> functionName(String label) {
        String node = label.stripLeading();
        return node.startsWith(FUNCTION_AST_PREFIX)
                ? Optional.of(firstTokenAfterPrefix(node, FUNCTION_AST_PREFIX))
                : Optional.empty();
    }

    /** The name an {@code Identifier} or {@code TableIdentifier} node refers to, without its alias. */
    private static Optional<String> referencedName(String label) {
        String node = withoutChildren(label);
        return Stream.of(IDENTIFIER_AST_PREFIX, TABLE_IDENTIFIER_AST_PREFIX)
                .filter(node::startsWith)
                .findFirst()
                .map(prefix -> AST_ALIAS_SUFFIX.matcher(node.substring(prefix.length())).replaceFirst(""));
    }

    private static Optional<String> alias(String label) {
        var matcher = AST_ALIAS_SUFFIX.matcher(withoutChildren(label));
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static String withoutChildren(String label) {
        return AST_CHILDREN_SUFFIX.matcher(label.stripLeading()).replaceFirst("");
    }

    /** A function directly under a table expression is a table function; reject it before resolving it. */
    private static boolean containsTableFunctionNode(List<String> nodeLabels) {
        for (int i = 0; i + 1 < nodeLabels.size(); i++) {
            String parent = nodeLabels.get(i);
            String child = nodeLabels.get(i + 1);
            if (parent.stripLeading().startsWith(TABLE_EXPRESSION_AST_PREFIX)
                    && child.stripLeading().startsWith(FUNCTION_AST_PREFIX)
                    && child.length() - child.stripLeading().length() > parent.length()
                            - parent.stripLeading().length()) {
                return true;
            }
        }
        return false;
    }

    /** The resolved tree includes implicit system.one for a FROM-less SELECT; explicit reads fail the AST check. */
    private boolean containsForbiddenResolvedTable(FreeFormSqlAccount account, List<String> queryTree) {
        return queryTree.stream()
                .anyMatch(line -> FreeFormSqlSubqueries.isTableFunctionNode(line)
                        || FreeFormSqlSubqueries.isTableNode(line) && FreeFormSqlSubqueries.tableName(line)
                                .filter(table -> isAllowedTable(account, table)).isEmpty());
    }

    private boolean isAllowedTable(FreeFormSqlAccount account, String table) {
        String prefix = database + ".";
        return table.equals("system.one")
                || table.startsWith(prefix) && ALLOWED_TABLES.get(account).contains(table.substring(prefix.length()));
    }

    /** A FUNCTION node whose name cannot be read counts as forbidden, so a tree format change fails closed. */
    private static boolean containsForbiddenOrUnreadableFunction(List<String> queryTree) {
        return queryTree.stream()
                .filter(FreeFormSqlSubqueries::isFunctionNode)
                .anyMatch(line -> FreeFormSqlSubqueries.resolvedFunctionName(line)
                        .map(name -> FreeFormSqlForbiddenFunctions.ALL.contains(name.toLowerCase(Locale.ROOT)))
                        .orElse(true));
    }

    private void recordSuccess(FreeFormSqlResult result, long startMillis) {
        duration.record(elapsed(startMillis), Outcome.SUCCESS.attributes);
        resultRows.record(result.resultRows());
        bytesRead.record(result.readBytes());
    }

    private WebApplicationException mapExecutionError(Throwable error, long startMillis) {
        if (findCause(error, NoSuchColumnException.class) != null) {
            return error(Outcome.OTHER, startMillis, Response.Status.BAD_REQUEST, RESULT_SHAPE_MESSAGE, error);
        }

        if (findCause(error, UncheckedIOException.class) != null) {
            // A non-JSON 'result' value is a bad query shape (caller's fault, not infra), so a 400 rather than a 503.
            return error(Outcome.OTHER, startMillis, Response.Status.BAD_REQUEST, INVALID_JSON_RESULT_MESSAGE, error);
        }

        ServerException serverException = findCause(error, ServerException.class);
        if (serverException == null) {
            // No response from ClickHouse (connection/transport failure): an infrastructure problem, not a bad query,
            // so surface it as 5xx for alerting instead of a misleading 400.
            return error(Outcome.OTHER, startMillis, Response.Status.SERVICE_UNAVAILABLE, EXECUTION_FAILED_MESSAGE,
                    error);
        }

        int code = serverException.getCode();
        if (CH_LIMIT_CODES.contains(code)) {
            // Constant message: the raw ClickHouse text (which can carry schema/tenant details) stays in logs only.
            return error(Outcome.CH_LIMIT, startMillis, Response.Status.BAD_REQUEST,
                    code == CH_TOO_MANY_BYTES ? BYTES_LIMIT_MESSAGE : LIMIT_MESSAGE, error);
        }
        if (code == CH_TOO_MANY_SIMULTANEOUS_QUERIES) {
            return busy(startMillis, error);
        }
        if (code == CH_ACCESS_DENIED) {
            return error(Outcome.PERMISSION_DENIED, startMillis, Response.Status.BAD_REQUEST,
                    ACCESS_DENIED_MESSAGE, error);
        }
        // ClickHouse responded with an error: the failure is attributable to the query, so return 4xx.
        return error(Outcome.OTHER, startMillis, Response.Status.BAD_REQUEST, EXECUTION_FAILED_MESSAGE, error);
    }

    private WebApplicationException reject(Outcome outcome, long startMillis, String message, Throwable cause) {
        log.info("Free-form SQL query rejected: {}", message, cause);
        return fail(outcome, startMillis, Response.Status.BAD_REQUEST, message);
    }

    private WebApplicationException busy(long startMillis, Throwable cause) {
        return error(Outcome.CH_BUSY, startMillis, Response.Status.TOO_MANY_REQUESTS,
                "Too many queries are running; retry shortly", cause);
    }

    private WebApplicationException error(Outcome outcome, long startMillis, Response.Status status, String message,
            Throwable cause) {
        log.warn("Free-form SQL query failed: {}", message, cause);
        return fail(outcome, startMillis, status, message);
    }

    private WebApplicationException fail(Outcome outcome, long startMillis, Response.Status status, String message) {
        duration.record(elapsed(startMillis), outcome.attributes);
        var response = Response.status(status).entity(new ErrorMessage(List.of(message)));
        if (status == Response.Status.TOO_MANY_REQUESTS) {
            response.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return switch (status) {
            case BAD_REQUEST -> new BadRequestException(response.build());
            case TOO_MANY_REQUESTS -> new ClientErrorException(response.build());
            case SERVICE_UNAVAILABLE -> new ServiceUnavailableException(response.build());
            default -> new InternalServerErrorException(response.build());
        };
    }

    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        return Throwables.getCausalChain(throwable).stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElse(null);
    }

    private static boolean isSyntaxError(Throwable error) {
        ServerException serverException = findCause(error, ServerException.class);
        return serverException != null && serverException.getCode() == CH_SYNTAX_ERROR;
    }

    private static boolean isTooManySimultaneousQueries(Throwable error) {
        ServerException serverException = findCause(error, ServerException.class);
        return serverException != null && serverException.getCode() == CH_TOO_MANY_SIMULTANEOUS_QUERIES;
    }

    private static long elapsed(long startMillis) {
        return System.currentTimeMillis() - startMillis;
    }
}
