package com.comet.opik.domain;

import com.comet.opik.api.FilterType;
import com.comet.opik.api.SavedFilter;
import com.comet.opik.api.SavedFilter.SavedFilterPage;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ImplementedBy(SavedFilterServiceImpl.class)
public interface SavedFilterService {

    SavedFilter create(SavedFilter savedFilter);

    SavedFilter update(UUID id, SavedFilter savedFilter);

    SavedFilter get(UUID id);

    void delete(UUID id);

    void delete(Set<UUID> ids);

    SavedFilterPage find(int page, int size, UUID projectId, String name, FilterType type,
            List<SortingField> sortingFields);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class SavedFilterServiceImpl implements SavedFilterService {

    private static final String FILTER_ALREADY_EXISTS = "Saved filter already exists";
    private static final String FILTER_NOT_FOUND = "Saved filter not found";

    private final @NonNull TransactionTemplate template;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;

    private NotFoundException createNotFoundError() {
        log.info(FILTER_NOT_FOUND);
        return new NotFoundException(FILTER_NOT_FOUND,
                Response.status(Response.Status.NOT_FOUND)
                        .entity(new ErrorMessage(List.of(FILTER_NOT_FOUND)))
                        .build());
    }

    @Override
    public SavedFilter create(@NonNull SavedFilter savedFilter) {
        UUID filterId = idGenerator.generateId();
        String userName = requestContext.get().getUserName();
        String workspaceId = requestContext.get().getWorkspaceId();

        SavedFilter newFilter = savedFilter.toBuilder()
                .id(filterId)
                .workspaceId(workspaceId)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .build();

        try {
            template.inTransaction(handle -> {
                var savedFilterDAO = handle.attach(SavedFilterDAO.class);

                String filtersJson = JsonUtils.writeValueAsString(savedFilter.filters());
                savedFilterDAO.save(workspaceId, newFilter, filtersJson);

                return null;
            });

            log.info("Created saved filter with id '{}', name '{}'", filterId, savedFilter.name());
            return newFilter;
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                log.info("Saved filter with name '{}' already exists", savedFilter.name());
                throw new EntityAlreadyExistsException(new ErrorMessage(List.of(FILTER_ALREADY_EXISTS)));
            }
            throw e;
        }
    }

    @Override
    public SavedFilter update(@NonNull UUID id, @NonNull SavedFilter savedFilter) {
        String userName = requestContext.get().getUserName();
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(handle -> {
            var savedFilterDAO = handle.attach(SavedFilterDAO.class);

            // Verify filter exists
            SavedFilter existing = savedFilterDAO.findById(id, workspaceId);
            if (existing == null) {
                throw createNotFoundError();
            }

            // Update filter
            String filtersJson = savedFilter.filters() != null
                    ? JsonUtils.writeValueAsString(savedFilter.filters())
                    : null;

            savedFilterDAO.update(id, workspaceId, savedFilter.name(), savedFilter.description(),
                    filtersJson, userName);

            log.info("Updated saved filter with id '{}'", id);
            return get(id);
        });
    }

    @Override
    public SavedFilter get(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(handle -> {
            var savedFilterDAO = handle.attach(SavedFilterDAO.class);
            SavedFilter filter = savedFilterDAO.findById(id, workspaceId);

            if (filter == null) {
                throw createNotFoundError();
            }

            return filter;
        });
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(handle -> {
            var savedFilterDAO = handle.attach(SavedFilterDAO.class);

            SavedFilter filter = savedFilterDAO.findById(id, workspaceId);
            if (filter == null) {
                throw createNotFoundError();
            }

            savedFilterDAO.delete(id, workspaceId);
            log.info("Deleted saved filter with id '{}'", id);
            return null;
        });
    }

    @Override
    public void delete(@NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(handle -> {
            var savedFilterDAO = handle.attach(SavedFilterDAO.class);
            savedFilterDAO.delete(ids, workspaceId);
            log.info("Deleted {} saved filters", ids.size());
            return null;
        });
    }

    @Override
    public SavedFilterPage find(int page, int size, UUID projectId, String name, FilterType type,
            List<SortingField> sortingFields) {
        String workspaceId = requestContext.get().getWorkspaceId();

        if (projectId == null) {
            throw new IllegalArgumentException("Project ID is required for finding saved filters");
        }

        return template.inTransaction(handle -> {
            var savedFilterDAO = handle.attach(SavedFilterDAO.class);

            String sortingQuery = sortingFields != null && !sortingFields.isEmpty()
                    ? sortingQueryBuilder.toOrderBySql(sortingFields)
                    : null;

            int offset = page * size;
            long total = savedFilterDAO.findCount(workspaceId, projectId, name, type);
            List<SavedFilter> filters = savedFilterDAO.find(size, offset, workspaceId, projectId, name, type,
                    sortingQuery);

            return SavedFilterPage.builder()
                    .page(page)
                    .size(filters.size())
                    .total(total)
                    .content(filters)
                    .sortableBy(List.of("name", "created_at", "last_updated_at"))
                    .build();
        });
    }
}
