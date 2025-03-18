package com.comet.opik.domain.attachment;

import com.comet.opik.api.attachment.CompleteMultipartUploadRequest;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContext;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(AttachmentDAOImpl.class)
public interface AttachmentDAO {

    Mono<Long> addAttachment(CompleteMultipartUploadRequest completeUploadRequest, UUID containerId, String mimeType);
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

    private final @NonNull TransactionTemplateAsync asyncTemplate;

    @Override
    public Mono<Long> addAttachment(@NonNull CompleteMultipartUploadRequest completeUploadRequest, UUID containerId,
            String mimeType) {
        return asyncTemplate.nonTransaction(connection -> {

            var statement = connection.createStatement(INSERT_ATTACHMENT);

            statement.bind("entity_id", completeUploadRequest.entityId())
                    .bind("entity_type", completeUploadRequest.entityType().getValue())
                    .bind("container_id", containerId)
                    .bind("file_name", completeUploadRequest.fileName())
                    .bind("mime_type", mimeType)
                    .bind("file_size", completeUploadRequest.fileSize());

            return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }
}
