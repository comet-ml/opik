package com.comet.opik.domain;

import com.comet.opik.api.DashboardSavedQuery;
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

@ImplementedBy(DashboardSavedQueryServiceImpl.class)
public interface DashboardSavedQueryService {

    DashboardSavedQuery create(DashboardSavedQuery query);

    Optional<DashboardSavedQuery> findById(UUID id);

    /** Which of {@code ids} are dashboard saved queries of the caller's workspace. */
    Set<UUID> findExistingIds(Set<UUID> ids);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class DashboardSavedQueryServiceImpl implements DashboardSavedQueryService {

    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;

    @Override
    public DashboardSavedQuery create(@NonNull DashboardSavedQuery query) {
        String workspaceId = requestContext.get().getWorkspaceId();
        var saved = query.toBuilder()
                .id(idGenerator.generateId())
                .createdBy(requestContext.get().getUserName())
                .build();

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DashboardSavedQueryDAO.class);
            dao.save(saved, workspaceId);
            log.info("Created dashboard saved query '{}' in workspace '{}', project '{}'", saved.id(),
                    workspaceId,
                    saved.projectId() == null ? "all" : saved.projectId());
            return dao.findById(saved.id(), workspaceId).orElseThrow();
        });
    }

    @Override
    public Optional<DashboardSavedQuery> findById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(DashboardSavedQueryDAO.class).findById(id, workspaceId));
    }

    @Override
    public Set<UUID> findExistingIds(@NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        String workspaceId = requestContext.get().getWorkspaceId();
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(DashboardSavedQueryDAO.class).findExistingIds(ids, workspaceId));
    }
}
