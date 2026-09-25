package com.comet.opik.domain;

import com.clickhouse.client.api.ServerException;
import com.clickhouse.client.api.metadata.NoSuchColumnException;
import com.comet.opik.api.AnalyticsQueryResponse;
import com.comet.opik.api.error.ErrorMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Throwables;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
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

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Orchestrates caller-supplied, read-only free-form SQL bounded to a single workspace/project. First account is the
 * Agent Insights subagent, but the service is intentionally feature-agnostic.
 *
 * <p>Every query is pre-flighted through {@code EXPLAIN AST} (see {@link FreeFormSqlQueryDAO}); if any node is a
 * {@code Set*} node (top-level, subquery, CTE, UNION arm or {@code FORMAT ... SETTINGS}) the query is rejected before
 * execution, and an unparseable query is rejected too — execution never runs on a validation failure. The
 * workspace/project bounds are pushed as ClickHouse server settings consumed by the restrictive row policies, never
 * concatenated into the SQL.
 */
@Slf4j
@Singleton
public class FreeFormSqlQueryService {

    public static final String METRIC_NAMESPACE = "opik.free_form_sql.queries";

    private static final AttributeKey<String> RESULT_KEY = stringKey("result");
    private static final AttributeKey<String> REASON_KEY = stringKey("reason");

    /**
     * The terminal outcome of a query, carrying its {@code (result, reason)} metric tags. The duration histogram is
     * tagged with these and its per-tag count is the query counter, so no separate counters are needed.
     */
    private enum Outcome {
        SUCCESS("success", "none"),
        SETTINGS_CLAUSE("rejected", "settings_clause_rejected"),
        PARSE_ERROR("rejected", "parse_error"),
        PERMISSION_DENIED("error", "permission_denied"),
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

    private final LongHistogram duration;
    private final LongHistogram resultRows;
    private final LongHistogram bytesRead;

    @Inject
    public FreeFormSqlQueryService(@NonNull FreeFormSqlQueryDAO freeFormSqlQueryDAO,
            @NonNull FreeFormSqlEntityNameEnricher entityNameEnricher) {
        this.freeFormSqlQueryDAO = freeFormSqlQueryDAO;
        this.entityNameEnricher = entityNameEnricher;

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
                .thenCompose(nodeLabels -> runQuery(account, workspaceId, projectScope, query, startMillis));
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
            String projectScope, String query, long startMillis) {
        return freeFormSqlQueryDAO.execute(account, workspaceId, projectScope, query)
                .handle((result, error) -> {
                    if (error != null) {
                        throw mapExecutionError(error, startMillis);
                    }
                    recordSuccess(result, startMillis);
                    return result;
                })
                .thenCompose(result -> resolveEntityNames(account, result, workspaceId));
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
