package com.comet.opik.domain;

import com.comet.opik.api.FeedbackDefinition;
import com.comet.opik.api.Page;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(FeedbackDefinitionServiceImpl.class)
public interface FeedbackDefinitionService {
    String MESSAGE = "Cannot delete feedback definition 'User feedback'";

    <E, T extends FeedbackDefinition<E>> T create(T feedback);

    <E, T extends FeedbackDefinition<E>> T update(UUID id, T feedback);

    void delete(UUID id);

    void delete(Set<UUID> ids);

    <E, T extends FeedbackDefinition<E>> T get(UUID id);

    Page<FeedbackDefinition<?>> find(int page, int size, FeedbackDefinitionCriteria criteria);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class FeedbackDefinitionServiceImpl implements FeedbackDefinitionService {

    private static final String FEEDBACK_ALREADY_EXISTS = "Feedback already exists";
    public static final String USER_FEEDBACK = "User feedback";

    private final @NonNull TransactionTemplate template;
    private final @NonNull IdGenerator generator;
    private final @NonNull Provider<RequestContext> requestContext;

    @Override
    public Page<FeedbackDefinition<?>> find(int page, int size, @NonNull FeedbackDefinitionCriteria criteria) {

        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {

            var repository = handle.attach(FeedbackDefinitionDAO.class);

            int offset = (page - 1) * size;

            List<FeedbackDefinition<?>> feedbacks = repository
                    .find(size, offset, workspaceId, criteria.name(), criteria.type())
                    .stream()
                    .map(feedback -> switch (feedback) {
                        case NumericalFeedbackDefinitionDefinitionModel numerical ->
                            FeedbackDefinitionMapper.INSTANCE.map(numerical);
                        case CategoricalFeedbackDefinitionDefinitionModel categorical ->
                            FeedbackDefinitionMapper.INSTANCE.map(categorical);
                        case BooleanFeedbackDefinitionDefinitionModel booleanDef ->
                            FeedbackDefinitionMapper.INSTANCE.map(booleanDef);
                    })
                    .toList();

            return new FeedbackDefinition.FeedbackDefinitionPage(page, feedbacks.size(),
                    repository.findCount(workspaceId, criteria.name(), criteria.type()), feedbacks);
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E, T extends FeedbackDefinition<E>> T get(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return (T) template.inTransaction(READ_ONLY, handle -> {

            var repository = handle.attach(FeedbackDefinitionDAO.class);

            return repository.findById(id, workspaceId)
                    .map(feedback -> (FeedbackDefinition<?>) switch (feedback) {
                        case NumericalFeedbackDefinitionDefinitionModel numerical ->
                            FeedbackDefinitionMapper.INSTANCE.map(numerical);
                        case CategoricalFeedbackDefinitionDefinitionModel categorical ->
                            FeedbackDefinitionMapper.INSTANCE.map(categorical);
                        case BooleanFeedbackDefinitionDefinitionModel booleanDef ->
                            FeedbackDefinitionMapper.INSTANCE.map(booleanDef);
                    })
                    .orElseThrow(this::createNotFoundError);
        });
    }

    @Override
    public <E, T extends FeedbackDefinition<E>> T create(@NonNull T feedback) {
        UUID id = generator.generateId();
        IdGenerator.validateVersion(id, "feedback");
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        try {
            return template.inTransaction(WRITE, handle -> {

                var dao = handle.attach(FeedbackDefinitionDAO.class);

                FeedbackDefinitionModel<?> model = switch (feedback) {
                    case FeedbackDefinition.NumericalFeedbackDefinition numerical -> {

                        var definition = numerical.toBuilder()
                                .id(id)
                                .createdBy(userName)
                                .lastUpdatedBy(userName)
                                .build();

                        yield FeedbackDefinitionMapper.INSTANCE.map(definition);
                    }

                    case FeedbackDefinition.CategoricalFeedbackDefinition categorical -> {
                        var definition = categorical.toBuilder()
                                .id(id)
                                .createdBy(userName)
                                .lastUpdatedBy(userName)
                                .build();

                        yield FeedbackDefinitionMapper.INSTANCE.map(definition);
                    }

                    case FeedbackDefinition.BooleanFeedbackDefinition booleanDef -> {
                        var definition = booleanDef.toBuilder()
                                .id(id)
                                .createdBy(userName)
                                .lastUpdatedBy(userName)
                                .build();

                        yield FeedbackDefinitionMapper.INSTANCE.map(definition);
                    }
                };

                dao.save(workspaceId, model);

                return get(id);
            });

        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                log.info(FEEDBACK_ALREADY_EXISTS);
                throw new EntityAlreadyExistsException(new ErrorMessage(List.of(FEEDBACK_ALREADY_EXISTS)));
            } else {
                throw e;
            }
        }
    }

    @Override
    public <E, T extends FeedbackDefinition<E>> T update(@NonNull UUID id, @NonNull T feedback) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        try {
            return template.inTransaction(WRITE, handle -> {

                var dao = handle.attach(FeedbackDefinitionDAO.class);

                dao.findById(id, workspaceId).orElseThrow(this::createNotFoundError);

                FeedbackDefinitionModel<?> model = switch (feedback) {
                    case FeedbackDefinition.NumericalFeedbackDefinition numerical ->
                        FeedbackDefinitionMapper.INSTANCE.map(numerical.toBuilder()
                                .lastUpdatedBy(userName)
                                .build());
                    case FeedbackDefinition.CategoricalFeedbackDefinition categorical ->
                        FeedbackDefinitionMapper.INSTANCE.map(categorical.toBuilder()
                                .lastUpdatedBy(userName)
                                .build());
                    case FeedbackDefinition.BooleanFeedbackDefinition booleanDef ->
                        FeedbackDefinitionMapper.INSTANCE.map(booleanDef.toBuilder()
                                .lastUpdatedBy(userName)
                                .build());
                };

                dao.update(id, model, workspaceId);

                return get(id);
            });
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                log.info(FEEDBACK_ALREADY_EXISTS);
                throw new EntityAlreadyExistsException(new ErrorMessage(List.of(FEEDBACK_ALREADY_EXISTS)));
            } else {
                throw e;
            }
        }
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(FeedbackDefinitionDAO.class);

            Set<UUID> ids = Set.of(id);

            validateDefinitionName(ids, dao, workspaceId);

            dao.delete(id, workspaceId);
            return null;
        });
    }

    @Override
    public void delete(Set<UUID> ids) {
        if (ids.isEmpty()) {
            log.info("ids list is empty, returning");
            return;
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            FeedbackDefinitionDAO dao = handle.attach(FeedbackDefinitionDAO.class);

            validateDefinitionName(ids, dao, workspaceId);

            dao.delete(ids, workspaceId);
            return null;
        });
    }

    private void validateDefinitionName(Set<UUID> ids, FeedbackDefinitionDAO dao, String workspaceId) {
        if (dao.containsNameByIds(ids, workspaceId, USER_FEEDBACK) > 0) {
            throw new ClientErrorException(MESSAGE, Response.Status.CONFLICT);
        }
    }

    private NotFoundException createNotFoundError() {
        String message = "Feedback definition not found";
        log.info(message);
        return new NotFoundException(message,
                Response.status(Response.Status.NOT_FOUND).entity(new ErrorMessage(List.of(message))).build());
    }
}
