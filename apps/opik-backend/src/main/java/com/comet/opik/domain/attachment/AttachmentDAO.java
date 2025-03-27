package com.comet.opik.domain.attachment;

import com.comet.opik.api.attachment.Attachment;
import com.comet.opik.api.attachment.AttachmentInfoHolder;
import com.comet.opik.api.attachment.AttachmentSearchCriteria;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContext;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(AttachmentDAOImpl.class)
public interface AttachmentDAO {

    Mono<Long> addAttachment(AttachmentInfoHolder attachmentInfo, String mimeType, long fileSize);

    Mono<Attachment.AttachmentPage> list(int page, int size, AttachmentSearchCriteria criteria);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AttachmentDAOImpl implements AttachmentDAO {

    private static final String INSERT_ATTACHMENT = """
            INSERT INTO attachments(
                entity_id,
                entity_type,
                container_id,
                workspace_id,
                file_name,
                mime_type,
                file_size,
                created_by,
                last_updated_by,
                last_updated_at
            )
            VALUES
            (
                 :entity_id,
                 :entity_type,
                 :container_id,
                 :workspace_id,
                 :file_name,
                 :mime_type,
                 :file_size,
                 :user_name,
                 :user_name,
                 now64(9)
            )
            ;
            """;

    private static final String FIND_COUNT = """
            SELECT count(file_name) as count
            FROM
            (
                SELECT file_name
                FROM attachments
                WHERE workspace_id = :workspace_id
                AND container_id = :container_id
                AND entity_type = :entity_type
                AND entity_id = :entity_id
                ORDER BY (workspace_id, container_id, entity_type, entity_id, file_name) DESC
                LIMIT 1 BY file_name
            ) as latest_rows
            ;
            """;

    private static final String FIND = """
            SELECT file_name, mime_type, file_size
            FROM
            (
                SELECT file_name, mime_type, file_size
                FROM attachments
                WHERE workspace_id = :workspace_id
                AND container_id = :container_id
                AND entity_type = :entity_type
                AND entity_id = :entity_id
                ORDER BY (workspace_id, container_id, entity_type, entity_id, file_name) DESC, last_updated_at DESC
                LIMIT 1 BY file_name
            ) as latest_rows
            LIMIT :limit OFFSET :offset
            ;
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;

    @Override
    public Mono<Long> addAttachment(@NonNull AttachmentInfoHolder attachmentInfo,
            @NonNull String mimeType, long fileSize) {
        return asyncTemplate.nonTransaction(connection -> {

            var statement = connection.createStatement(INSERT_ATTACHMENT);

            statement.bind("entity_id", attachmentInfo.entityId())
                    .bind("entity_type", attachmentInfo.entityType().getValue())
                    .bind("container_id", attachmentInfo.containerId())
                    .bind("file_name", attachmentInfo.fileName())
                    .bind("mime_type", mimeType)
                    .bind("file_size", fileSize);

            return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    @Override
    public Mono<Attachment.AttachmentPage> list(int page, int size, @NonNull AttachmentSearchCriteria criteria) {
        return asyncTemplate.nonTransaction(connection -> countTotal(criteria, connection)
                .flatMap(result -> Mono.from(result.map((row, rowMetadata) -> row.get("count", Long.class))))
                .flatMap(total -> find(page, size, criteria, connection)
                        .flatMapMany(this::mapToDto)
                        .collectList()
                        .map(content -> new Attachment.AttachmentPage(page, content.size(), total, content,
                                List.of()))));
    }

    private Mono<? extends Result> countTotal(
            AttachmentSearchCriteria criteria, Connection connection) {
        var statement = connection.createStatement(FIND_COUNT);
        bindSearchCriteria(statement, criteria);
        return makeMonoContextAware(bindWorkspaceIdToMono(statement));
    }

    private Mono<? extends Result> find(
            int page, int size, AttachmentSearchCriteria criteria, Connection connection) {
        int offset = (page - 1) * size;
        var statement = connection.createStatement(FIND);
        statement.bind("limit", size)
                .bind("offset", offset);
        bindSearchCriteria(statement, criteria);

        return makeMonoContextAware(bindWorkspaceIdToMono(statement));
    }

    private void bindSearchCriteria(Statement statement, AttachmentSearchCriteria criteria) {
        statement.bind("container_id", criteria.containerId())
                .bind("entity_type", criteria.entityType().getValue())
                .bind("entity_id", criteria.entityId());
    }

    private Publisher<Attachment> mapToDto(Result result) {
        return result.map((row, rowMetadata) -> Attachment.builder()
                .fileName(row.get("file_name", String.class))
                .mimeType(row.get("mime_type", String.class))
                .fileSize(row.get("file_size", Long.class))
                .build());
    }
}
