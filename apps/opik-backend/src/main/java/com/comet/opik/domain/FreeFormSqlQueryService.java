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
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Orchestrates caller-supplied, read-only free-form SQL bounded to a single workspace/project. First consumer is the
 * Agent Insights subagent, but the service is intentionally feature-agnostic.
 *
 * <p>Every query is pre-flighted through {@code EXPLAIN AST} (see {@link FreeFormSqlQueryDAO}); if any node is a
 * {@code Set*} node (top-level, subquery, CTE, UNION arm or {@code FORMAT ... SETTINGS}) the query is rejected before
 * execution, and an unparseable query is rejected too — execution never runs on a validation failure. The
 * workspace/project bounds are pushed as ClickHouse server settings consumed by the restrictive row policies, never
 * concatenated into the SQL.
 *
 * <p>On top of the row policies, a post-run check verifies from the query's {@code system.query_log} entries and its
 * plan that every table it read was read under a row policy ({@link FreeFormSqlPolicyCheck}). A scalar subquery
 * reading a table cannot be shown either way. What both do depends on {@link FreeFormSqlPostRunCheckConfig.Mode}:
 * <ul>
 * <li>{@code off}: neither runs.</li>
 * <li>{@code audit}: a scalar read is reported and the query runs; the check runs after the response is returned and
 * only reports. Nothing either finds, and no failure of either, affects the request.</li>
 * <li>{@code enforce}: a scalar read is rejected before running, with a rewrite the caller can apply; results are
 * returned only once the check passes, and are withheld on a violation, a missing log entry or a failure.</li>
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
        SCALAR_SUBQUERY_NOT_ALLOWED("rejected", "scalar_subquery_not_allowed"),
        POLICY_UNVERIFIED("error", "policy_unverified"),
        POLICY_CHECK_FAILED("error", "policy_check_failed"),
        CH_LIMIT("error", "ch_limit"),
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

    /** Returned to the caller, an LLM agent, so it says what to change and how. */
    static final String SCALAR_SUBQUERY_MESSAGE = "Query rejected: a scalar subquery (a subquery used as a value, "
            + "e.g. SELECT (SELECT count() FROM spans)) cannot read a table; this one reads %s. Compute the value in a "
            + "CTE and select from it instead, e.g. WITH s AS (SELECT count() AS n FROM spans) "
            + "SELECT toJSONString(map('n', toString(s.n))) AS result FROM s, or join it to the main query. "
            + "Subqueries under IN and EXISTS are allowed.";

    /** ClickHouse error codes surfaced to the caller as a clean 4xx rather than a 500. */
    private static final int CH_TOO_MANY_ROWS = 158;
    private static final int CH_TIMEOUT_EXCEEDED = 159;
    private static final int CH_MEMORY_LIMIT_EXCEEDED = 241;
    private static final int CH_TOO_MANY_ROWS_OR_BYTES = 396;
    private static final int CH_ACCESS_DENIED = 497;
    private static final int CH_SYNTAX_ERROR = 62;
    private static final Set<Integer> CH_LIMIT_CODES = Set.of(
            CH_TOO_MANY_ROWS, CH_TIMEOUT_EXCEEDED, CH_MEMORY_LIMIT_EXCEEDED, CH_TOO_MANY_ROWS_OR_BYTES);

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
    private enum CheckOutcome {
        VERIFIED("verified"),
        VIOLATION("violation"),
        LOG_MISSING("log_missing"),
        CHECK_FAILED("check_failed"),
        SCALAR_READ("scalar_read");

        private final String tag;

        CheckOutcome(String tag) {
            this.tag = tag;
        }
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
                .thenCompose(nodeLabels -> subqueryReads(account, workspaceId, projectScope, query, startMillis))
                .thenCompose(subqueryReads -> runQuery(account, workspaceId, projectScope, query, subqueryReads,
                        startMillis));
    }

    /** Where the query's subqueries read, for the check; unknown when it is off or could not tell in audit. */
    private CompletableFuture<FreeFormSqlSubqueries.SubqueryReads> subqueryReads(FreeFormSqlAccount account,
            String workspaceId,
            String projectScope, String query, long startMillis) {
        if (mode == FreeFormSqlPostRunCheckConfig.Mode.OFF) {
            return CompletableFuture.completedFuture(FreeFormSqlSubqueries.SubqueryReads.UNKNOWN);
        }
        var queryTree = freeFormSqlQueryDAO.explainQueryTree(account, workspaceId, projectScope, query);
        if (mode == FreeFormSqlPostRunCheckConfig.Mode.ENFORCE) {
            return queryTree.handle((tree, error) -> rejectScalarReads(account, tree, error, startMillis));
        }
        return queryTree.handle((tree, error) -> {
            if (error != null) {
                // Audit never affects the request: the query runs, and reports its own error if it has one.
                log.warn("Free-form SQL scalar subquery check could not run for account '{}'", account, error);
                count(CheckOutcome.CHECK_FAILED, account);
                return FreeFormSqlSubqueries.SubqueryReads.UNKNOWN;
            }
            var reads = FreeFormSqlSubqueries.subqueryReads(tree, database);
            if (reads.hasScalar()) {
                log.warn("Free-form SQL query reads {} in a scalar subquery (audit, not rejected), account '{}'",
                        describe(reads), account);
                count(CheckOutcome.SCALAR_READ, account);
            }
            return reads;
        });
    }

    /**
     * A scalar subquery reading a table cannot be shown to have run under its row policy (see
     * {@link FreeFormSqlPolicyCheck}), so it is rejected before running, with the rewrite the caller can apply.
     */
    private FreeFormSqlSubqueries.SubqueryReads rejectScalarReads(FreeFormSqlAccount account, List<String> queryTree,
            Throwable error,
            long startMillis) {
        if (error != null) {
            throw findCause(error, WebApplicationException.class) != null
                    ? findCause(error, WebApplicationException.class)
                    : mapExecutionError(error, startMillis);
        }
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
                    ? reject(Outcome.PARSE_ERROR, startMillis, "Query rejected: could not be parsed", error)
                    : mapExecutionError(error, startMillis);
        }
        if (containsSetNode(nodeLabels)) {
            throw reject(Outcome.SETTINGS_CLAUSE, startMillis,
                    "Query rejected: SETTINGS/SET clauses are not allowed", null);
        }
        return nodeLabels;
    }

    private CompletableFuture<AnalyticsQueryResponse> runQuery(FreeFormSqlAccount account, String workspaceId,
            String projectScope, String query, FreeFormSqlSubqueries.SubqueryReads subqueryReads, long startMillis) {
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
                        // After the response, off its path: nothing the check finds or fails on reaches the caller.
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
            check(account, workspaceId, projectScope, query, queryId, subqueryReads).whenComplete((outcome, error) -> {
                if (error != null) {
                    log.warn("Free-form SQL post-run policy check could not run for query '{}'", queryId, error);
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
                log.error("Free-form SQL post-run policy check could not run for query '{}'", queryId, error);
                count(CheckOutcome.CHECK_FAILED, account);
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
                .map(label -> label.stripLeading().split("\\s", 2)[0])
                .anyMatch(SETTINGS_AST_NODES::contains);
    }

    private void recordSuccess(FreeFormSqlResult result, long startMillis) {
        duration.record(elapsed(startMillis), Outcome.SUCCESS.attributes);
        resultRows.record(result.resultRows());
        bytesRead.record(result.readBytes());
    }

    private WebApplicationException mapExecutionError(Throwable error, long startMillis) {
        if (findCause(error, NoSuchColumnException.class) != null) {
            return error(Outcome.OTHER, startMillis, Response.Status.BAD_REQUEST,
                    "Query must return exactly one column named 'result'", error);
        }

        if (findCause(error, UncheckedIOException.class) != null) {
            // A non-JSON 'result' value is a bad query shape (caller's fault, not infra), so a 400 rather than a 503.
            return error(Outcome.OTHER, startMillis, Response.Status.BAD_REQUEST,
                    "Query 'result' column must contain valid JSON produced via toJSONString(...)", error);
        }

        ServerException serverException = findCause(error, ServerException.class);
        if (serverException == null) {
            // No response from ClickHouse (connection/transport failure): an infrastructure problem, not a bad query,
            // so surface it as 5xx for alerting instead of a misleading 400.
            return error(Outcome.OTHER, startMillis, Response.Status.SERVICE_UNAVAILABLE, "Query execution failed",
                    error);
        }

        int code = serverException.getCode();
        if (CH_LIMIT_CODES.contains(code)) {
            // Constant message: the raw ClickHouse text (which can carry schema/tenant details) stays in logs only.
            return error(Outcome.CH_LIMIT, startMillis, Response.Status.BAD_REQUEST,
                    "Query exceeded ClickHouse limits", error);
        }
        if (code == CH_ACCESS_DENIED) {
            return error(Outcome.PERMISSION_DENIED, startMillis, Response.Status.BAD_REQUEST,
                    "Query rejected: access denied", error);
        }
        // ClickHouse responded with an error: the failure is attributable to the query, so return 4xx.
        return error(Outcome.OTHER, startMillis, Response.Status.BAD_REQUEST, "Query execution failed", error);
    }

    private WebApplicationException reject(Outcome outcome, long startMillis, String message, Throwable cause) {
        log.info("Free-form SQL query rejected: {}", message, cause);
        return fail(outcome, startMillis, Response.Status.BAD_REQUEST, message);
    }

    private WebApplicationException error(Outcome outcome, long startMillis, Response.Status status, String message,
            Throwable cause) {
        log.warn("Free-form SQL query failed: {}", message, cause);
        return fail(outcome, startMillis, status, message);
    }

    private WebApplicationException fail(Outcome outcome, long startMillis, Response.Status status, String message) {
        duration.record(elapsed(startMillis), outcome.attributes);
        var response = Response.status(status).entity(new ErrorMessage(List.of(message))).build();
        return switch (status) {
            case BAD_REQUEST -> new BadRequestException(response);
            case SERVICE_UNAVAILABLE -> new ServiceUnavailableException(response);
            default -> new InternalServerErrorException(response);
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

    private static long elapsed(long startMillis) {
        return System.currentTimeMillis() - startMillis;
    }
}
