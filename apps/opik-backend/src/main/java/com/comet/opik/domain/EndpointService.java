package com.comet.opik.domain;

import com.comet.opik.api.Endpoint;
import com.comet.opik.api.Endpoint.EndpointPage;
import com.comet.opik.api.EndpointUpdate;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.sorting.SortingFactoryEndpoints;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(EndpointServiceImpl.class)
public interface EndpointService {

    Endpoint create(@NonNull Endpoint endpoint);

    Endpoint get(@NonNull UUID id);

    EndpointPage find(int page, int size, @NonNull UUID projectId, String name, List<SortingField> sortingFields);

    Endpoint update(@NonNull UUID id, @NonNull EndpointUpdate endpoint);

    void delete(@NonNull UUID id);

    void delete(@NonNull Set<UUID> ids);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class EndpointServiceImpl implements EndpointService {

    private static final String ENDPOINT_NOT_FOUND = "Endpoint not found";
    private static final String ENDPOINT_ALREADY_EXISTS = "Endpoint already exists";

    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull SortingFactoryEndpoints sortingFactory;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;

    @Override
    public Endpoint create(@NonNull Endpoint endpoint) {
        UUID id = idGenerator.generateId();
        IdGenerator.validateVersion(id, "endpoint");

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        var newEndpoint = endpoint.toBuilder()
                .id(id)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        try {
            template.inTransaction(WRITE, handle -> {
                var dao = handle.attach(EndpointDAO.class);
                dao.save(workspaceId, newEndpoint);
                return null;
            });

            return get(id);
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                throw new EntityAlreadyExistsException(new ErrorMessage(List.of(ENDPOINT_ALREADY_EXISTS)));
            }
            throw e;
        }
    }

    @Override
    public Endpoint get(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting endpoint by id '{}' on workspace_id '{}'", id, workspaceId);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(EndpointDAO.class);
            return dao.fetch(id, workspaceId)
                    .orElseThrow(() -> new NotFoundException(ENDPOINT_NOT_FOUND));
        });
    }

    @Override
    public EndpointPage find(int page, int size, @NonNull UUID projectId, String name,
            List<SortingField> sortingFields) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String sortingFieldsSql = sortingQueryBuilder.toOrderBySql(sortingFields);

        log.info("Find endpoints by projectId '{}' on workspaceId '{}'", projectId, workspaceId);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(EndpointDAO.class);

            String nameTerm = StringUtils.isNotBlank(name) ? name.trim() : null;
            int offset = (page - 1) * size;

            long total = dao.findCount(workspaceId, projectId, nameTerm);
            List<Endpoint> endpoints = dao.find(size, offset, workspaceId, projectId, nameTerm, sortingFieldsSql);

            return EndpointPage.builder()
                    .content(endpoints)
                    .page(page)
                    .size(endpoints.size())
                    .total(total)
                    .sortableBy(sortingFactory.getSortableFields())
                    .build();
        });
    }

    @Override
    public Endpoint update(@NonNull UUID id, @NonNull EndpointUpdate endpointUpdate) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        try {
            template.inTransaction(WRITE, handle -> {
                var dao = handle.attach(EndpointDAO.class);
                dao.update(id, workspaceId, userName, endpointUpdate);
                return null;
            });

            return get(id);
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                throw new EntityAlreadyExistsException(new ErrorMessage(List.of(ENDPOINT_ALREADY_EXISTS)));
            }
            throw e;
        }
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            handle.attach(EndpointDAO.class).delete(id, workspaceId);
            return null;
        });
    }

    @Override
    public void delete(@NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            handle.attach(EndpointDAO.class).delete(ids, workspaceId);
            return null;
        });
    }
}
