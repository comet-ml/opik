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
import java.util.Map;
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
     * The reserved {@code SQL_project_id} value meaning "every project in that workspace". The row policies match it
     * explicitly, so an unset or empty setting matches no branch and returns nothing — a dropped setting fails
     * closed rather than silently widening the query to the workspace.
     */
    String PROJECT_ID_ALL = "*";

    /**
     * Executes {@code query} bounded to the given workspace and project, reading the single {@code result}
     * column. {@code projectId} is a single project's id, or {@link #PROJECT_ID_ALL}. {@code queryId} identifies the
     * execution in {@code system.query_log} for the post-run check.
     */
    CompletableFuture<FreeFormSqlResult> execute(FreeFormSqlAccount account, String workspaceId,
            String projectId, String query, String queryId);

    /**
     * Returns the resolved {@code EXPLAIN QUERY TREE} of {@code query}, one line per row, under the settings
     * {@link #execute} sends. Resolving it evaluates the query's scalar subqueries, as the read-only account.
     */
    CompletableFuture<List<String>> explainQueryTree(FreeFormSqlAccount account, String workspaceId,
            String projectId, String query);

    /**
     * Returns {@code EXPLAIN json = 1, actions = 1} of {@code query} under exactly the settings {@link #execute} sends,
     * so the reads it shows are the ones that ran. Note that EXPLAIN evaluates scalar and IN/EXISTS subqueries.
     */
    CompletableFuture<String> explainPlan(FreeFormSqlAccount account, String workspaceId,
            String projectId, String query);

    /**
     * Reads the finished {@code system.query_log} entries of {@code queryId}, initial and shard-side, from every
     * replica, with the tables covered by the applied row policies that apply to {@code user}, the account the query
     * ran as. One read, narrowed to the query id and the last 15 minutes. Runs on the main analytics account: the read-only ones cannot read the log.
     */
    CompletableFuture<List<FreeFormSqlQueryLogEntry>> fetchQueryLog(String queryId, String user);

}

@Singleton
@Slf4j
class FreeFormSqlQueryDAOImpl implements FreeFormSqlQueryDAO {

    private static final String EXPLAIN_AST_COLUMN = "explain";
    private static final String RESULT_COLUMN = "result";
    private static final String EXPLAIN_AST_PREFIX = "EXPLAIN AST ";
    private static final String EXPLAIN_PLAN_PREFIX = "EXPLAIN json = 1, actions = 1 ";
    private static final String EXPLAIN_QUERY_TREE_PREFIX = "EXPLAIN QUERY TREE ";

    /** Custom server settings the row policies read via getSetting(...). Sent as URL params; the SQL is left untouched. */
    private static final String SETTING_WORKSPACE_ID = "SQL_workspace_id";
    private static final String SETTING_PROJECT_ID = "SQL_project_id";

    /**
     * Each applied policy resolved to the table it covers through system.row_policies, among the policies that apply
     * to the account the query ran as; any other, or an unknown one, covers nothing.
     */
    static final String QUERY_LOG_ENTRIES = """
            SELECT is_initial_query, user, tables,
                arrayFilter(t -> t != '', arrayMap(p -> transform(p,
                    (SELECT groupArray(name) FROM system.row_policies
                        WHERE apply_to_all OR has(apply_to_list, {user:String})),
                    (SELECT groupArray(concat(database, '.', table)) FROM system.row_policies
                        WHERE apply_to_all OR has(apply_to_list, {user:String})), ''),
                    used_row_policies)) AS policy_covered_tables
            FROM clusterAllReplicas('{cluster}', system.query_log)
            WHERE event_date >= yesterday() AND event_time >= now() - INTERVAL 15 MINUTE
                AND initial_query_id = {query_id:String} AND type = 'QueryFinish'
            """;

    private final Client readOnlyClient;
    private final Client extendedReadOnlyClient;
    private final Client analyticsClient;

    @Inject
    FreeFormSqlQueryDAOImpl(
            @Named(DatabaseAnalyticsModule.READ_ONLY_FREE_FORM_SQL_CLICKHOUSE_CLIENT) @NonNull Client readOnlyClient,
            @Named(DatabaseAnalyticsModule.READ_ONLY_FREE_FORM_EXTENDED_SQL_CLICKHOUSE_CLIENT) @NonNull Client extendedReadOnlyClient,
            @NonNull Client analyticsClient) {
        this.readOnlyClient = readOnlyClient;
        this.extendedReadOnlyClient = extendedReadOnlyClient;
        this.analyticsClient = analyticsClient;
    }

    private Client clientFor(FreeFormSqlAccount account) {
        return account == FreeFormSqlAccount.EXTENDED ? extendedReadOnlyClient : readOnlyClient;
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
            @NonNull String workspaceId, @NonNull String projectId, @NonNull String query, @NonNull String queryId) {
        // Only the SQL_ custom settings are sent: readonly=1 rejects any other per-query setting.
        // Execution/memory/row caps are pinned on the read-only user's server-side profile.
        var settings = new QuerySettings()
                .setQueryId(queryId)
                .serverSetting(SETTING_WORKSPACE_ID, workspaceId)
                .serverSetting(SETTING_PROJECT_ID, projectId);

        return clientFor(account).queryRecords(query, settings)
                .thenApply(FreeFormSqlQueryDAOImpl::readResult);
    }

    @Override
    @WithSpan
    public CompletableFuture<List<String>> explainQueryTree(@NonNull FreeFormSqlAccount account,
            @NonNull String workspaceId, @NonNull String projectId, @NonNull String query) {
        var settings = new QuerySettings()
                .serverSetting(SETTING_WORKSPACE_ID, workspaceId)
                .serverSetting(SETTING_PROJECT_ID, projectId);
        return clientFor(account).queryRecords(EXPLAIN_QUERY_TREE_PREFIX + query, settings)
                .thenApply(FreeFormSqlQueryDAOImpl::readNodeLabels);
    }

    @Override
    @WithSpan
    public CompletableFuture<String> explainPlan(@NonNull FreeFormSqlAccount account, @NonNull String workspaceId,
            @NonNull String projectId, @NonNull String query) {
        var settings = new QuerySettings()
                .serverSetting(SETTING_WORKSPACE_ID, workspaceId)
                .serverSetting(SETTING_PROJECT_ID, projectId);
        return clientFor(account).queryRecords(EXPLAIN_PLAN_PREFIX + query, settings)
                .thenApply(records -> String.join("\n", readNodeLabels(records)));
    }

    @Override
    @WithSpan
    public CompletableFuture<List<FreeFormSqlQueryLogEntry>> fetchQueryLog(@NonNull String queryId,
            @NonNull String user) {
        return analyticsClient
                .queryRecords(QUERY_LOG_ENTRIES, Map.<String, Object>of("query_id", queryId, "user", user))
                .thenApply(FreeFormSqlQueryDAOImpl::readLogEntries);
    }

    private static List<FreeFormSqlQueryLogEntry> readLogEntries(Records records) {
        try (records) {
            return StreamSupport.stream(records.spliterator(), false)
                    .map(row -> FreeFormSqlQueryLogEntry.builder()
                            .initial(row.getInteger("is_initial_query") == 1)
                            .user(row.getString("user"))
                            .tables(row.<String>getList("tables"))
                            .policyCoveredTables(row.<String>getList("policy_covered_tables"))
                            .build())
                    .toList();
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
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
