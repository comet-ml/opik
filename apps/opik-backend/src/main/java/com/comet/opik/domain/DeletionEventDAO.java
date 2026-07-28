package com.comet.opik.domain;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import io.r2dbc.spi.Row;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.FilterUtils.getLogComment;
import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;

/**
 * Owns the {@code deletion_events_local} bridge table. A lightweight delete does not change a row's version
 * column, so a delete issued while a table is being copied into a new layout would be missed by the copy and
 * the row would reappear; recording the deleted ids here lets the copy replay them. Callers insert the ids they
 * delete and read them back per source table.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DeletionEventDAO {

    private static final String INSERT = """
            INSERT INTO deletion_events_local (
                source_table,
                workspace_id,
                project_id,
                deleted_id,
                deletion_reason
            )
            SETTINGS log_comment = '<log_comment>'
            FORMAT Values
                <items:{item |
                    (
                        :source_table<item.index>,
                        :workspace_id<item.index>,
                        :project_id<item.index>,
                        :deleted_id<item.index>,
                        :deletion_reason<item.index>
                    )
                    <if(item.hasNext)>,<endif>
                }>
            ;
            """;

    private static final String FIND_BY_SOURCE_TABLE_AND_DELETED_IDS = """
            SELECT
                event_time,
                source_table,
                workspace_id,
                project_id,
                deleted_id,
                deletion_reason
            FROM deletion_events_local
            WHERE source_table = :source_table
            AND deleted_id IN :deleted_ids
            SETTINGS log_comment = '<log_comment>'
            """;

    private final @NonNull TransactionTemplateAsync templateAsync;
    private final @NonNull @Config OpikConfiguration config;

    /**
     * Inserts the given events, letting ClickHouse stamp {@code event_time} via the column default.
     * {@code userName} tags the query log with the user whose request triggered the capture. Returns no count:
     * ClickHouse does not report affected rows for {@code INSERT}.
     */
    public Mono<Void> insert(Set<DeletionEvent> events, String userName) {
        if (CollectionUtils.isEmpty(events)) {
            return Mono.empty();
        }
        var batchSize = config.getDatabaseAnalyticsDataModel().deletionEventsInsertBatchSize();
        var batches = Lists.partition(List.copyOf(events), batchSize);
        log.info("Inserting deletion events in batch, total '{}', batches '{}', batchSize '{}'",
                events.size(), batches.size(), batchSize);
        return Flux.fromIterable(batches)
                .concatMap(batch -> insert(batch, userName))
                .then();
    }

    private Mono<Void> insert(List<DeletionEvent> events, String userName) {
        var first = events.getFirst();
        log.info("Inserting deletion events batch, size '{}' for source_table '{}' project_id '{}' on workspace '{}'",
                events.size(), first.sourceTable().getValue(), first.projectId(), first.workspaceId());
        var details = "source_table=%s, project_id=%s".formatted(first.sourceTable().getValue(), first.projectId());
        var logComment = getLogComment("insert_deletion_events", first.workspaceId(), userName, details);
        var template = TemplateUtils.getBatchSql(INSERT, events.size());
        template.add("log_comment", logComment);
        return templateAsync.nonTransaction(connection -> {
            var statement = connection.createStatement(template.render());
            int i = 0;
            for (var event : events) {
                statement.bind("source_table" + i, event.sourceTable().getValue())
                        .bind("workspace_id" + i, event.workspaceId())
                        // project_id is empty for workspace-scoped source tables
                        .bind("project_id" + i, event.projectId() == null ? "" : event.projectId().toString())
                        .bind("deleted_id" + i, event.deletedId())
                        .bind("deletion_reason" + i, event.deletionReason().getValue());
                i++;
            }
            // Consume the result so the statement completes and any execution error surfaces; the
            // updated-row count is discarded (ClickHouse reports 0 for INSERT).
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .then();
        });
    }

    @VisibleForTesting
    Flux<DeletionEvent> findBySourceTableAndDeletedIds(
            @NonNull SourceTable sourceTable, @NonNull Set<String> deletedIds) {
        var details = "source_table=%s, deleted_ids_size=%s".formatted(sourceTable.getValue(), deletedIds.size());
        var template = getSTWithLogComment(
                FIND_BY_SOURCE_TABLE_AND_DELETED_IDS, "find_deletion_events", null, null, details);
        return templateAsync.stream(connection -> Flux.from(connection.createStatement(template.render())
                .bind("source_table", sourceTable.getValue())
                .bind("deleted_ids", deletedIds)
                .execute())
                .flatMap(result -> result.map((row, _) -> map(row))));
    }

    private DeletionEvent map(Row row) {
        var projectId = row.get("project_id", String.class);
        return DeletionEvent.builder()
                .eventTime(row.get("event_time", Instant.class))
                .sourceTable(SourceTable.fromStringOrThrow(row.get("source_table", String.class)))
                .workspaceId(row.get("workspace_id", String.class))
                .projectId(StringUtils.isBlank(projectId) ? null : UUID.fromString(projectId))
                .deletedId(row.get("deleted_id", String.class))
                .deletionReason(DeletionReason.fromStringOrThrow(row.get("deletion_reason", String.class)))
                .build();
    }
}
