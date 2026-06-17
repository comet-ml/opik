package com.comet.opik.domain;

import com.clickhouse.client.api.ServerException;
import com.clickhouse.client.api.metadata.NoSuchColumnException;
import com.comet.opik.api.AnalyticsQueryResponse;
import com.comet.opik.api.error.ErrorMessage;
import com.google.common.base.Throwables;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

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
 */
@Slf4j
@Singleton
public class FreeFormSqlQueryService {

    public static final String METRIC_NAMESPACE = "opik.free_form_sql.queries";

    private static final AttributeKey<String> RESULT_KEY = stringKey("result");
    private static final AttributeKey<String> REASON_KEY = stringKey("reason");

    // The terminal outcome of a query, carrying its (result, reason) metric tags. The duration histogram is tagged
    // with these and its per-tag count is the query counter, so no separate counters are needed.
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

    // The parser models every SETTINGS/SET clause (top-level, subquery, CTE, FORMAT ... SETTINGS) as this AST node.
    // Verified against ClickHouse 25.3.x: EXPLAIN AST prints it as the leading token "Set". Matching the exact node
    // token (not a "Set" prefix) avoids false positives on Set-prefixed identifiers and keeps intent explicit; the
    // SETTINGS rejection tests fail loudly if a future ClickHouse version renames the node.
    private static final Set<String> SETTINGS_AST_NODES = Set.of("Set");

    // ClickHouse error codes surfaced to the caller as a clean 4xx rather than a 500.
    private static final int CH_TOO_MANY_ROWS = 158;
    private static final int CH_TIMEOUT_EXCEEDED = 159;
    private static final int CH_MEMORY_LIMIT_EXCEEDED = 241;
    private static final int CH_TOO_MANY_ROWS_OR_BYTES = 396;
    private static final int CH_ACCESS_DENIED = 497;
    private static final int CH_SYNTAX_ERROR = 62;
    private static final Set<Integer> CH_LIMIT_CODES = Set.of(
            CH_TOO_MANY_ROWS, CH_TIMEOUT_EXCEEDED, CH_MEMORY_LIMIT_EXCEEDED, CH_TOO_MANY_ROWS_OR_BYTES);

    private final FreeFormSqlQueryDAO freeFormSqlQueryDAO;

    private final LongHistogram duration;
    private final LongHistogram resultRows;
    private final LongHistogram bytesRead;

    @Inject
    public FreeFormSqlQueryService(@NonNull FreeFormSqlQueryDAO freeFormSqlQueryDAO) {
        this.freeFormSqlQueryDAO = freeFormSqlQueryDAO;

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

    public Mono<AnalyticsQueryResponse> executeQuery(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull String query) {
        long startMillis = System.currentTimeMillis();

        return freeFormSqlQueryDAO.explainAst(query)
                // A failed EXPLAIN AST is a hard reject: never fall through to execution. A genuine syntax error
                // (unparseable input, statement-stacking) is reported as parse_error; any other failure (transport,
                // permissions, ...) is routed through the standard execution-error mapping so it isn't mislabelled.
                .onErrorMap(error -> isSyntaxError(error)
                        ? reject(Outcome.PARSE_ERROR, startMillis, "Query rejected: could not be parsed", error)
                        : mapExecutionError(error, startMillis))
                .flatMap(nodeLabels -> containsSetNode(nodeLabels)
                        ? Mono.error(reject(Outcome.SETTINGS_CLAUSE, startMillis,
                                "Query rejected: SETTINGS/SET clauses are not allowed", null))
                        : runQuery(workspaceId, projectId, query, startMillis));
    }

    private Mono<AnalyticsQueryResponse> runQuery(String workspaceId, UUID projectId, String query, long startMillis) {
        return freeFormSqlQueryDAO.execute(workspaceId, projectId, query)
                .map(result -> {
                    recordSuccess(result, startMillis);
                    return AnalyticsQueryResponse.builder().results(result.rows()).build();
                })
                .onErrorMap(error -> mapExecutionError(error, startMillis));
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
