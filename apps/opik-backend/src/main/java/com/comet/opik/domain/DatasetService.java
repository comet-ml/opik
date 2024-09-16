package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetCriteria;
import com.comet.opik.api.DatasetIdentifier;
import com.comet.opik.api.DatasetUpdate;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.AsyncUtils;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.comet.opik.api.Dataset.DatasetPage;
import static com.comet.opik.domain.ExperimentItemDAO.ExperimentSummary;
import static com.comet.opik.infrastructure.db.TransactionTemplate.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplate.WRITE;
import static java.util.stream.Collectors.toMap;

@ImplementedBy(DatasetServiceImpl.class)
public interface DatasetService {

    Dataset save(Dataset dataset);

    UUID getOrCreate(String workspaceId, String name, String userName);

    void update(UUID id, DatasetUpdate dataset);

    Dataset findById(UUID id);

    Dataset findById(UUID id, String workspaceId);

    List<Dataset> findByIds(Set<UUID> ids, String workspaceId);

    Dataset findByName(String workspaceId, String name);

    void delete(DatasetIdentifier identifier);

    void delete(UUID id);

    DatasetPage find(int page, int size, DatasetCriteria criteria);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetServiceImpl implements DatasetService {

    private static final String DATASET_ALREADY_EXISTS = "Dataset already exists";

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull ExperimentItemDAO experimentItemDAO;

    @Override
    public Dataset save(@NonNull Dataset dataset) {

        var builder = dataset.id() == null
                ? dataset.toBuilder().id(idGenerator.generateId())
                : dataset.toBuilder();

        String userName = requestContext.get().getUserName();
        String workspaceId = requestContext.get().getWorkspaceId();

        builder
                .createdBy(userName)
                .lastUpdatedBy(userName);

        var newDataset = builder.build();

        IdGenerator.validateVersion(newDataset.id(), "dataset");

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetDAO.class);

            try {
                dao.save(newDataset, workspaceId);
                return dao.findById(newDataset.id(), workspaceId).orElseThrow();
            } catch (UnableToExecuteStatementException e) {
                if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                    log.info(DATASET_ALREADY_EXISTS);
                    throw new EntityAlreadyExistsException(new ErrorMessage(List.of(DATASET_ALREADY_EXISTS)));
                } else {
                    throw e;
                }
            }
        });
    }

    @Override
    public UUID getOrCreate(@NonNull String workspaceId, @NonNull String name, @NonNull String userName) {
        var dataset = template.inTransaction(READ_ONLY,
                handle -> handle.attach(DatasetDAO.class).findByName(workspaceId, name));

        if (dataset.isEmpty()) {
            UUID id = idGenerator.generateId();
            log.info("Creating dataset with id '{}', name '{}', workspaceId '{}'", id, name, workspaceId);
            template.inTransaction(WRITE, handle -> {
                handle.attach(DatasetDAO.class)
                        .save(
                                Dataset.builder()
                                        .id(id)
                                        .name(name)
                                        .createdBy(userName)
                                        .lastUpdatedBy(userName)
                                        .build(),
                                workspaceId);
                return null;
            });
            log.info("Created dataset with id '{}', name '{}', workspaceId '{}'", id, name, workspaceId);
            return id;
        }

        UUID id = dataset.get().id();
        log.info("Got dataset with id '{}', name '{}', workspaceId '{}'", id, name, workspaceId);
        return id;
    }

    @Override
    public void update(@NonNull UUID id, @NonNull DatasetUpdate dataset) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetDAO.class);

            try {
                int result = dao.update(workspaceId, id, dataset, userName);

                if (result == 0) {
                    throw newNotFoundException();
                }
            } catch (UnableToExecuteStatementException e) {
                if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                    log.info(DATASET_ALREADY_EXISTS);
                    throw new EntityAlreadyExistsException(new ErrorMessage(List.of(DATASET_ALREADY_EXISTS)));
                } else {
                    throw e;
                }
            }

            return null;
        });
    }

    @Override
    public Dataset findById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();
        Dataset dataset = findById(id, workspaceId);

        Map<UUID, ExperimentSummary> experimentSummary = experimentItemDAO
                .findExperimentSummaryByDatasetIds(List.of(dataset.id()))
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, requestContext))
                .toStream()
                .collect(toMap(ExperimentSummary::datasetId, Function.identity()));

        var summary = experimentSummary.computeIfAbsent(dataset.id(), ExperimentSummary::empty);

        return dataset.toBuilder()
                .experimentCount(summary.experimentCount())
                .mostRecentExperimentAt(summary.mostRecentExperimentAt())
                .build();
    }

    @Override
    public Dataset findById(@NonNull UUID id, @NonNull String workspaceId) {
        log.info("Finding dataset with id '{}', workspaceId '{}'", id, workspaceId);
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            var dataset = dao.findById(id, workspaceId).orElseThrow(this::newNotFoundException);
            log.info("Found dataset with id '{}', workspaceId '{}'", id, workspaceId);
            return dataset;
        });
    }

    @Override
    public List<Dataset> findByIds(@NonNull Set<UUID> ids, @NonNull String workspaceId) {
        if (ids.isEmpty()) {
            log.info("Returning empty datasets for empty ids, workspaceId '{}'", workspaceId);
            return List.of();
        }
        log.info("Finding datasets with ids '{}', workspaceId '{}'", ids, workspaceId);
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            var datasets = dao.findByIds(ids, workspaceId);
            log.info("Found datasets with ids '{}', workspaceId '{}'", ids, workspaceId);
            return datasets;
        });
    }

    @Override
    public Dataset findByName(@NonNull String workspaceId, @NonNull String name) {
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetDAO.class);

            Dataset dataset = dao.findByName(workspaceId, name).orElseThrow(this::newNotFoundException);

            log.info("Found dataset with name '{}', id '{}', workspaceId '{}'", name, dataset.id(), workspaceId);
            return dataset;
        });
    }

    @Override
    public void delete(@NonNull DatasetIdentifier identifier) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            dao.delete(workspaceId, identifier.datasetName());
            return null;
        });
    }

    private NotFoundException newNotFoundException() {
        String message = "Dataset not found";
        log.info(message);
        return new NotFoundException(message,
                Response.status(Response.Status.NOT_FOUND).entity(new ErrorMessage(List.of(message))).build());
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            dao.delete(id, workspaceId);
            return null;
        });
    }

    @Override
    public DatasetPage find(int page, int size, DatasetCriteria criteria) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {

            var repository = handle.attach(DatasetDAO.class);

            int offset = (page - 1) * size;

            List<Dataset> datasets = repository.find(size, offset, workspaceId, criteria.name());
            long count = repository.findCount(workspaceId, criteria.name());

            List<UUID> ids = datasets.stream().map(Dataset::id).toList();

            Map<UUID, ExperimentSummary> experimentSummary = experimentItemDAO.findExperimentSummaryByDatasetIds(ids)
                    .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, requestContext))
                    .toStream()
                    .collect(toMap(ExperimentSummary::datasetId, Function.identity()));

            return new DatasetPage(datasets.stream()
                    .map(dataset -> {
                        var resume = experimentSummary.computeIfAbsent(dataset.id(), ExperimentSummary::empty);

                        return dataset.toBuilder()
                                .experimentCount(resume.experimentCount())
                                .mostRecentExperimentAt(resume.mostRecentExperimentAt())
                                .build();
                    })
                    .toList(), page, datasets.size(), count);
        });
    }
}
