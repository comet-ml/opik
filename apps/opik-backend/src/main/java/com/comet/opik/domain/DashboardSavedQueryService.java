package com.comet.opik.domain;

import com.comet.opik.api.DashboardSavedQuery;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.base.Throwables;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(DashboardSavedQueryServiceImpl.class)
public interface DashboardSavedQueryService {

    /**
     * Stores {@code query} after the checks a widget run applies before executing it, so SQL a run would reject is
     * refused with the same 400 and message instead of being stored.
     */
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
    private final @NonNull FreeFormSqlQueryService freeFormSqlQueryService;

    @Override
    public DashboardSavedQuery create(@NonNull DashboardSavedQuery query) {
        String workspaceId = requestContext.get().getWorkspaceId();
        validate(workspaceId, query);
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

    /**
     * Checks the query as a widget run would: the EXTENDED account, its project, and the date range placeholders
     * bound. Any bound dates do, as the checks look at the query's shape, not its rows.
     */
    private void validate(String workspaceId, DashboardSavedQuery query) {
        String sql = DashboardWidgetQueryServiceImpl.bindWindow(query.sql(), Instant.EPOCH, Instant.EPOCH);
        try {
            freeFormSqlQueryService.validate(FreeFormSqlAccount.EXTENDED, workspaceId, query.projectId(), sql).join();
        } catch (CompletionException e) {
            // Unwrap so the mapped WebApplicationException, and its HTTP status, reaches the JAX-RS handling.
            Throwables.throwIfUnchecked(e.getCause());
            throw e;
        }
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
