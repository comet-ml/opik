package com.comet.opik.domain;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QuerySettings;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

/**
 * Read-only ClickHouse access for caller-supplied free-form SQL. All queries run on the dedicated
 * {@code comet_readonly_freeform_sql_user} client; the workspace/project bounds are passed as server settings
 * (URL params) so the SQL text is never modified. Higher-level validation, metrics and error mapping live in
 * {@link FreeFormSqlQueryService}.
 */
@ImplementedBy(FreeFormSqlQueryDAOImpl.class)
public interface FreeFormSqlQueryDAO {

    /**
     * Parses {@code query} via {@code EXPLAIN AST} (without executing it) and returns the AST node labels, one per row.
     */
    Mono<List<String>> explainAst(String query);

    /**
     * Executes {@code query} bounded to the given workspace/project and reads the single {@code result} column.
     */
    Mono<FreeFormSqlResult> execute(String workspaceId, UUID projectId, String query);
}

@Singleton
@Slf4j
class FreeFormSqlQueryDAOImpl implements FreeFormSqlQueryDAO {

    private static final String EXPLAIN_AST_COLUMN = "explain";
    private static final String RESULT_COLUMN = "result";
    private static final String EXPLAIN_AST_PREFIX = "EXPLAIN AST ";

    // Custom server settings the row policies read via getSetting(...). Sent as URL params; the SQL is left untouched.
    private static final String SETTING_WORKSPACE_ID = "SQL_workspace_id";
    private static final String SETTING_PROJECT_ID = "SQL_project_id";

    private final Client readOnlyClient;

    @Inject
    FreeFormSqlQueryDAOImpl(
            @Named(DatabaseAnalyticsModule.READ_ONLY_FREE_FORM_SQL_CLICKHOUSE_CLIENT) @NonNull Client readOnlyClient) {
        this.readOnlyClient = readOnlyClient;
    }

    @Override
    @WithSpan
    public Mono<List<String>> explainAst(@NonNull String query) {
        return Mono.fromFuture(() -> readOnlyClient.queryRecords(EXPLAIN_AST_PREFIX + query))
                .flatMap(records -> Mono.fromCallable(() -> {
                    try (records) {
                        return StreamSupport.stream(records.spliterator(), false)
                                .map(node -> node.getString(EXPLAIN_AST_COLUMN))
                                .toList();
                    }
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    @WithSpan
    public Mono<FreeFormSqlResult> execute(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull String query) {
        var settings = new QuerySettings()
                .serverSetting(SETTING_WORKSPACE_ID, workspaceId)
                .serverSetting(SETTING_PROJECT_ID, projectId.toString());

        return Mono.fromFuture(() -> readOnlyClient.queryRecords(query, settings))
                .flatMap(records -> Mono.fromCallable(() -> {
                    try (records) {
                        List<JsonNode> rows = StreamSupport.stream(records.spliterator(), false)
                                .map(record -> JsonUtils.getJsonNodeFromString(record.getString(RESULT_COLUMN)))
                                .toList();
                        return FreeFormSqlResult.builder()
                                .rows(rows)
                                .resultRows(records.getResultRows())
                                .readBytes(records.getReadBytes())
                                .build();
                    }
                }).subscribeOn(Schedulers.boundedElastic()));
    }
}
