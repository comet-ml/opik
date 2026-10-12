package com.comet.opik.domain;

import com.comet.opik.api.SavedQuery;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(SavedQueryServiceImpl.class)
public interface SavedQueryService {

    SavedQuery create(SavedQuery query);

    Optional<SavedQuery> findById(UUID id);

    /** Which of {@code ids} are saved queries of the caller's workspace. */
    Set<UUID> findExistingIds(Set<UUID> ids);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class SavedQueryServiceImpl implements SavedQueryService {

    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;

    @Override
    public SavedQuery create(@NonNull SavedQuery query) {
        String workspaceId = requestContext.get().getWorkspaceId();
        var saved = query.toBuilder()
                .id(idGenerator.generateId())
                .createdBy(requestContext.get().getUserName())
                .build();

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(SavedQueryDAO.class);
            dao.save(saved, workspaceId);
            log.info("Saved query '{}' in workspace '{}', project '{}'", saved.id(), workspaceId,
                    saved.projectId() == null ? "all" : saved.projectId());
            return dao.findById(saved.id(), workspaceId).orElseThrow();
        });
    }

    @Override
    public Optional<SavedQuery> findById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(SavedQueryDAO.class).findById(id, workspaceId));
    }

    @Override
    public Set<UUID> findExistingIds(@NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        String workspaceId = requestContext.get().getWorkspaceId();
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(SavedQueryDAO.class).findExistingIds(ids, workspaceId));
    }
}
