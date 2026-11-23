package com.comet.opik.domain;

import com.comet.opik.api.GuardrailTrainingRun;
import com.comet.opik.api.GuardrailTrainingRun.GuardrailTrainingRunPage;
import com.comet.opik.api.GuardrailTrainingRunStatus;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * The training runs of guardrail models: their rows in MySQL, in the request's workspace, and their jobs on
 * opik-inference, which {@link #submit} and {@link #reconcile} will drive once opik-inference serves its training jobs
 * API. Every method joins a transaction its caller has open, so the models service writes a model and its run
 * together.
 */
@ImplementedBy(GuardrailTrainingServiceImpl.class)
public interface GuardrailTrainingService {

    /** A new training's first attempt, QUEUED. */
    GuardrailTrainingRun create(UUID modelId);

    GuardrailTrainingRun get(UUID modelId, UUID runId);

    /** The model's runs, newest first. */
    GuardrailTrainingRunPage find(UUID modelId, int page, int size);

    List<GuardrailTrainingRun> findByIds(Collection<UUID> ids);

    /** The run a check with the model is served by: its most recent COMPLETED one. */
    Optional<GuardrailTrainingRun> findServingRun(UUID modelId);

    Optional<GuardrailTrainingRun> findInFlight(UUID modelId);

    /** 404 for a run the model does not have, 409 once the run has ended. */
    void cancel(UUID modelId, UUID runId);

    /** Deletes the model's runs and returns their ids, from which their S3 keys derive. */
    List<UUID> deleteByModel(UUID modelId);

    /** Submits the run's spec to opik-inference's training jobs API and moves the run to RUNNING. */
    void submit(GuardrailTrainingRun run);

    /** Brings the run in step with its job, on each tick of the run worker: stage, completion, or failure and retry. */
    void reconcile(GuardrailTrainingRun run);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class GuardrailTrainingServiceImpl implements GuardrailTrainingService {

    private static final String RUN_NOT_FOUND = "Guardrail training run not found";
    private static final String JOBS_NOT_IMPLEMENTED = "Training jobs on opik-inference are not implemented yet";
    private static final String FILES_LAYOUT = "v1";
    private static final Set<GuardrailTrainingRunStatus> IN_FLIGHT = Set.of(GuardrailTrainingRunStatus.QUEUED,
            GuardrailTrainingRunStatus.RUNNING);

    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;

    @Override
    public GuardrailTrainingRun create(@NonNull UUID modelId) {
        var runId = idGenerator.generateId();
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        var run = template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(GuardrailTrainingRunDAO.class);
            dao.insert(runId, modelId, workspaceId, 1, createdMetadata(), userName);
            return dao.findById(runId, workspaceId).orElseThrow();
        });
        log.info("Created guardrail training run '{}' of model '{}' in workspace '{}'", runId, modelId, workspaceId);
        return run;
    }

    @Override
    public GuardrailTrainingRun get(@NonNull UUID modelId, @NonNull UUID runId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> handle.attach(GuardrailTrainingRunDAO.class)
                .findById(runId, workspaceId)
                .filter(run -> run.modelId().equals(modelId))
                .orElseThrow(() -> new NotFoundException(RUN_NOT_FOUND)));
    }

    @Override
    public GuardrailTrainingRunPage find(@NonNull UUID modelId, int page, int size) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(GuardrailTrainingRunDAO.class);
            var runs = dao.findByModel(modelId, workspaceId, size, (page - 1) * size);
            return GuardrailTrainingRunPage.builder()
                    .content(runs)
                    .page(page)
                    .size(runs.size())
                    .total(dao.countByModel(modelId, workspaceId))
                    .build();
        });
    }

    @Override
    public List<GuardrailTrainingRun> findByIds(@NonNull Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(GuardrailTrainingRunDAO.class).findByIds(ids, workspaceId));
    }

    @Override
    public Optional<GuardrailTrainingRun> findServingRun(@NonNull UUID modelId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(GuardrailTrainingRunDAO.class).findServingRun(modelId, workspaceId));
    }

    @Override
    public Optional<GuardrailTrainingRun> findInFlight(@NonNull UUID modelId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(GuardrailTrainingRunDAO.class).findInFlight(modelId, workspaceId));
    }

    @Override
    public void cancel(@NonNull UUID modelId, @NonNull UUID runId) {
        var run = get(modelId, runId);
        String userName = requestContext.get().getUserName();

        // One conditional write, no retry: inside a caller's transaction a second read would see the same snapshot. A
        // run that moved meanwhile is a 409 the member can retry.
        boolean cancelled = IN_FLIGHT.contains(run.status())
                && template.inTransaction(WRITE, handle -> handle.attach(GuardrailTrainingRunDAO.class)
                        .updateStatus(run.id(), run.workspaceId(), run.status(), GuardrailTrainingRunStatus.CANCELLED,
                                "Cancelled by %s".formatted(userName), null, userName)) > 0;
        if (!cancelled) {
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorMessage(List.of("Guardrail training run has already ended")))
                    .build());
        }
        log.info("Cancelled guardrail training run '{}' of model '{}' in workspace '{}'", runId, modelId,
                run.workspaceId());
    }

    @Override
    public List<UUID> deleteByModel(@NonNull UUID modelId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        var runIds = template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(GuardrailTrainingRunDAO.class);
            var ids = dao.findIdsByModel(modelId, workspaceId);
            dao.deleteByModel(modelId, workspaceId);
            return ids;
        });
        log.info("Deleted '{}' guardrail training runs of model '{}' in workspace '{}'", runIds.size(), modelId,
                workspaceId);
        return runIds;
    }

    @Override
    public void submit(@NonNull GuardrailTrainingRun run) {
        throw new UnsupportedOperationException(JOBS_NOT_IMPLEMENTED);
    }

    @Override
    public void reconcile(@NonNull GuardrailTrainingRun run) {
        throw new UnsupportedOperationException(JOBS_NOT_IMPLEMENTED);
    }

    /** A run's metadata at creation: the S3 key layout its dataset and adapter are written with. */
    private static ObjectNode createdMetadata() {
        var metadata = JsonUtils.createObjectNode();
        metadata.putObject("files").put("layout", FILES_LAYOUT);
        return metadata;
    }
}
