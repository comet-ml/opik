package com.comet.opik.domain;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.client.api.query.Records;
import com.comet.opik.infrastructure.db.DatabaseAnalyticsModule;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;

/**
 * Read-only ClickHouse access for caller-supplied free-form SQL. Queries run on one of two dedicated accounts,
 * chosen per call by {@link FreeFormSqlAccount}; the workspace/project bounds are passed as server settings
 * (URL params) so the SQL text is never modified. Higher-level validation, metrics and error mapping live in
 * {@link FreeFormSqlQueryService}.
 *
 * <p>The ClickHouse v2 client is natively async ({@link CompletableFuture}); this DAO stays on that API and never
 * blocks — the request is terminated at the endpoint.
 */
@ImplementedBy(FreeFormSqlQueryDAOImpl.class)
public interface FreeFormSqlQueryDAO {

    /**
     * Parses {@code query} via {@code EXPLAIN AST} (without executing it) and returns the AST node labels, one per row.
     */
    CompletableFuture<List<String>> explainAst(FreeFormSqlAccount account, String query);

    /**
     * The {@code SQL_project_id} value meaning "every project in that workspace". The row policies match it
     * explicitly, so an unset or empty setting matches no branch and returns nothing — a dropped setting fails
     * closed rather than silently widening the query to the workspace.
     */
    String PROJECT_SCOPE_ALL = "*";

    /**
     * Executes {@code query} bounded to the given workspace and project scope, reading the single {@code result}
     * column. {@code projectScope} is a project id, or {@link #PROJECT_SCOPE_ALL}.
     */
    CompletableFuture<FreeFormSqlResult> execute(FreeFormSqlAccount account, String workspaceId, String projectScope,
            String query);
}

@Singleton
@Slf4j
class FreeFormSqlQueryDAOImpl implements FreeFormSqlQueryDAO {

    private static final String EXPLAIN_AST_COLUMN = "explain";
    private static final String RESULT_COLUMN = "result";
    private static final String EXPLAIN_AST_PREFIX = "EXPLAIN AST ";

    /** Custom server settings the row policies read via getSetting(...). Sent as URL params; the SQL is left untouched. */
    private static final String SETTING_WORKSPACE_ID = "SQL_workspace_id";
    private static final String SETTING_PROJECT_ID = "SQL_project_id";

    private final Client agentInsightsClient;
    private final Client freeFormExtendedSqlClient;

    @Inject
    FreeFormSqlQueryDAOImpl(
            @Named(DatabaseAnalyticsModule.READ_ONLY_FREE_FORM_SQL_CLICKHOUSE_CLIENT) @NonNull Client agentInsightsClient,
            @Named(DatabaseAnalyticsModule.READ_ONLY_FREE_FORM_EXTENDED_SQL_CLICKHOUSE_CLIENT) @NonNull Client freeFormExtendedSqlClient) {
        this.agentInsightsClient = agentInsightsClient;
        this.freeFormExtendedSqlClient = freeFormExtendedSqlClient;
    }

    private Client clientFor(FreeFormSqlAccount account) {
        return account == FreeFormSqlAccount.EXTENDED ? freeFormExtendedSqlClient : agentInsightsClient;
    }

    @Override
    @WithSpan
    public CompletableFuture<List<String>> explainAst(@NonNull FreeFormSqlAccount account, @NonNull String query) {
        return clientFor(account).queryRecords(EXPLAIN_AST_PREFIX + query)
                .thenApply(FreeFormSqlQueryDAOImpl::readNodeLabels);
    }

    @Override
    @WithSpan
    public CompletableFuture<FreeFormSqlResult> execute(@NonNull FreeFormSqlAccount account,
            @NonNull String workspaceId, @NonNull String projectScope, @NonNull String query) {
        // Only the SQL_ custom settings are sent: readonly=1 rejects any other per-query setting.
        // Execution/memory/row caps are pinned on the read-only user's server-side profile.
        var settings = new QuerySettings()
                .serverSetting(SETTING_WORKSPACE_ID, workspaceId)
                .serverSetting(SETTING_PROJECT_ID, projectScope);

        return clientFor(account).queryRecords(query, settings)
                .thenApply(FreeFormSqlQueryDAOImpl::readResult);
    }

    /**
     * try-with-resources closes {@link Records}, whose {@code close()} is a checked {@code Exception}; these run as a
     * {@code thenApply} {@code Function}, which can't propagate checked exceptions, so we convert it to unchecked
     * (RuntimeExceptions pass through unwrapped so the service's error mapping still sees the original cause).
     */
    private static List<String> readNodeLabels(Records records) {
        try (records) {
            return StreamSupport.stream(records.spliterator(), false)
                    .map(node -> node.getString(EXPLAIN_AST_COLUMN))
                    .toList();
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    private static FreeFormSqlResult readResult(Records records) {
        try (records) {
            List<JsonNode> rows = StreamSupport.stream(records.spliterator(), false)
                    .map(record -> JsonUtils.getJsonNodeFromString(record.getString(RESULT_COLUMN)))
                    .toList();
            return FreeFormSqlResult.builder()
                    .rows(rows)
                    .resultRows(records.getResultRows())
                    .readBytes(records.getReadBytes())
                    .build();
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }
}
