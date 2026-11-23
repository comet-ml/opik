package com.comet.opik.domain;

import com.comet.opik.api.GuardrailModel;
import com.comet.opik.api.GuardrailModel.GuardrailModelPage;
import com.comet.opik.api.GuardrailTrainingRun;
import com.comet.opik.api.GuardrailTrainingRunStatus;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.sorting.SortingFactoryGuardrailModels;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
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
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * The guardrail models a workspace trains, in the request's workspace. Their runs are the
 * {@link GuardrailTrainingService}'s, which every write here calls inside its own transaction.
 */
@ImplementedBy(GuardrailModelServiceImpl.class)
public interface GuardrailModelService {

    /** A model and its first training run, in one transaction. 409 for a name the workspace has, ignoring case. */
    UUID create(String name, String description);

    GuardrailModel get(UUID id);

    GuardrailModelPage find(int page, int size, String name, boolean usableOnly,
            GuardrailTrainingRunStatus latestRunStatus, List<SortingField> sortingFields);

    void updateDescription(UUID id, String description);

    /** A new training of the model, its run's id. 409 while a run of the model is in flight. */
    UUID retrain(UUID id);

    /** 409 while a policy rule references the model. Deletes its runs with it. */
    void delete(UUID id);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class GuardrailModelServiceImpl implements GuardrailModelService {

    private static final String MODEL_NOT_FOUND = "Guardrail model not found";
    private static final String MODEL_ALREADY_EXISTS = "Guardrail model already exists";

    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull GuardrailTrainingService trainingService;
    private final @NonNull SortingFactoryGuardrailModels sortingFactory;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;

    @Override
    public UUID create(@NonNull String name, @NonNull String description) {
        var id = idGenerator.generateId();
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        template.inTransaction(WRITE, handle -> {
            try {
                handle.attach(GuardrailModelDAO.class).save(id, workspaceId, name, description, userName);
            } catch (UnableToExecuteStatementException exception) {
                if (exception.getCause() instanceof SQLIntegrityConstraintViolationException) {
                    throw new EntityAlreadyExistsException(new ErrorMessage(List.of(MODEL_ALREADY_EXISTS)));
                }
                throw exception;
            }
            trainingService.create(id);
            return null;
        });
        log.info("Created guardrail model, id '{}', name '{}', workspaceId '{}'", id, name, workspaceId);
        return id;
    }

    @Override
    public GuardrailModel get(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        var model = template.inTransaction(READ_ONLY, handle -> handle.attach(GuardrailModelDAO.class)
                .findById(id, workspaceId)
                .orElseThrow(() -> new NotFoundException(MODEL_NOT_FOUND)));
        return withRuns(List.of(model)).getFirst();
    }

    @Override
    public GuardrailModelPage find(int page, int size, String name, boolean usableOnly,
            GuardrailTrainingRunStatus latestRunStatus, @NonNull List<SortingField> sortingFields) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String nameTerm = Optional.ofNullable(StringUtils.trimToNull(name))
                .map(GuardrailPolicyDAO::escapeLike)
                .orElse(null);
        String sortingFieldsSql = sortingQueryBuilder.toOrderBySql(sortingFields);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(GuardrailModelDAO.class);
            long total = dao.count(workspaceId, nameTerm, usableOnly, latestRunStatus);
            var pageIds = dao.findPageIds(workspaceId, nameTerm, usableOnly, latestRunStatus, sortingFieldsSql, size,
                    (page - 1) * size);
            Map<UUID, GuardrailModel> byId = pageIds.isEmpty()
                    ? Map.of()
                    : dao.findByIds(pageIds, workspaceId).stream()
                            .collect(Collectors.toMap(GuardrailModel::id, Function.identity()));
            var models = withRuns(pageIds.stream().map(byId::get).toList());
            return GuardrailModelPage.builder()
                    .content(models)
                    .page(page)
                    .size(models.size())
                    .total(total)
                    .sortableBy(sortingFactory.getSortableFields())
                    .build();
        });
    }

    @Override
    public void updateDescription(@NonNull UUID id, @NonNull String description) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        int updated = template.inTransaction(WRITE, handle -> handle.attach(GuardrailModelDAO.class)
                .updateDescription(id, workspaceId, description, userName));
        if (updated == 0) {
            throw new NotFoundException(MODEL_NOT_FOUND);
        }
        log.info("Updated guardrail model description, id '{}', workspaceId '{}'", id, workspaceId);
    }

    @Override
    public UUID retrain(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        // One run in flight per model is the in-flight check below. The model row's lock only keeps two retrains from
        // racing past that check together: taken first, before any read, it makes a second retrain wait until the
        // first commits, so the second one's check sees the first one's run. Its absence is also the 404.
        var runId = template.inTransaction(WRITE, handle -> {
            if (handle.attach(GuardrailModelDAO.class).lock(id, workspaceId).isEmpty()) {
                throw new NotFoundException(MODEL_NOT_FOUND);
            }
            if (trainingService.findInFlight(id).isPresent()) {
                throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                        .entity(new ErrorMessage(List.of("Guardrail model has a training run in flight")))
                        .build());
            }
            return trainingService.create(id).id();
        });
        log.info("Retraining guardrail model, id '{}', workspaceId '{}', runId '{}'", id, workspaceId, runId);
        return runId;
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        // Under the model row's lock, so no retrain starts a run between the runs' delete and the model's.
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(GuardrailModelDAO.class);
            if (dao.lock(id, workspaceId).isEmpty()) {
                return null;
            }
            if (dao.isUsedByCustomClassifierRule(id, workspaceId)) {
                throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                        .entity(new ErrorMessage(List.of("Guardrail model is used by a policy rule")))
                        .build());
            }
            trainingService.deleteByModel(id);
            dao.delete(id, workspaceId);
            return null;
        });
        log.info("Deleted guardrail model, id '{}', workspaceId '{}'", id, workspaceId);
    }

    /** The models with their serving and latest runs, read in one query. */
    private List<GuardrailModel> withRuns(List<GuardrailModel> models) {
        var runIds = models.stream()
                .flatMap(model -> Stream.of(model.servingRunId(), model.latestRunId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        var runsById = trainingService.findByIds(runIds).stream()
                .collect(Collectors.toMap(GuardrailTrainingRun::id, Function.identity()));
        return models.stream()
                .map(model -> model.toBuilder()
                        .servingRun(runsById.get(model.servingRunId()))
                        .latestRun(runsById.get(model.latestRunId()))
                        .build())
                .toList();
    }
}
