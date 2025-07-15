package com.comet.opik.domain;

import com.comet.opik.api.Tag;
import com.comet.opik.api.TagCreate;
import com.comet.opik.api.TagUpdate;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Service
@RequiredArgsConstructor(onConstructor_ = @jakarta.inject.Inject)
@Slf4j
public class TagService {

    private final @NonNull TagDAO tagDAO;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplateAsync transactionTemplate;

    public Mono<List<Tag>> getTagsByWorkspaceId(String workspaceId) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(TagDAO.class);
            return repository.findByWorkspaceId(workspaceId);
        });
    }

    public Mono<Optional<Tag>> getTagByIdAndWorkspaceId(UUID id, String workspaceId) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(TagDAO.class);
            return repository.findByIdAndWorkspaceId(id, workspaceId);
        });
    }

    public Mono<List<Tag>> searchTagsByWorkspaceId(String workspaceId, String searchTerm) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(TagDAO.class);
            return repository.searchByWorkspaceId(workspaceId, searchTerm);
        });
    }

    public Mono<Tag> createTag(TagCreate tagCreate, String workspaceId) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(TagDAO.class);

            // Check if tag with same name already exists
            Optional<Tag> existingTag = repository.findByNameAndWorkspaceId(tagCreate.name(), workspaceId);
            if (existingTag.isPresent()) {
                throw new DuplicateKeyException(
                        "Tag with name '" + tagCreate.name() + "' already exists in this workspace");
            }

            UUID id = idGenerator.generate();
            Tag tag = new Tag(
                    id,
                    tagCreate.name(),
                    tagCreate.description(),
                    workspaceId,
                    null, // created_at will be set by database
                    null // updated_at will be set by database
            );

            repository.insert(tag);

            // Fetch the created tag to get the timestamps
            return repository.findByIdAndWorkspaceId(id, workspaceId)
                    .orElseThrow(() -> new RuntimeException("Failed to create tag"));
        });
    }

    public Mono<Tag> updateTag(UUID id, TagUpdate tagUpdate, String workspaceId) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(TagDAO.class);

            // Check if tag exists
            Optional<Tag> existingTag = repository.findByIdAndWorkspaceId(id, workspaceId);
            if (existingTag.isEmpty()) {
                throw new RuntimeException("Tag not found");
            }

            // Check if new name conflicts with existing tag (excluding current tag)
            Optional<Tag> conflictingTag = repository.findByNameAndWorkspaceId(tagUpdate.name(), workspaceId);
            if (conflictingTag.isPresent() && !conflictingTag.get().id().equals(id)) {
                throw new DuplicateKeyException(
                        "Tag with name '" + tagUpdate.name() + "' already exists in this workspace");
            }

            int updatedRows = repository.update(id, tagUpdate.name(), tagUpdate.description(), workspaceId);
            if (updatedRows == 0) {
                throw new RuntimeException("Failed to update tag");
            }

            // Fetch the updated tag
            return repository.findByIdAndWorkspaceId(id, workspaceId)
                    .orElseThrow(() -> new RuntimeException("Failed to fetch updated tag"));
        });
    }

    public Mono<Void> deleteTag(UUID id, String workspaceId) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(TagDAO.class);

            int deletedRows = repository.deleteByIdAndWorkspaceId(id, workspaceId);
            if (deletedRows == 0) {
                throw new RuntimeException("Tag not found or could not be deleted");
            }

            return null;
        });
    }

    public Mono<Long> getTagCountByWorkspaceId(String workspaceId) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(TagDAO.class);
            return repository.countByWorkspaceId(workspaceId);
        });
    }
}