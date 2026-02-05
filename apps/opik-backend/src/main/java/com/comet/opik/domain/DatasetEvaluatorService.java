package com.comet.opik.domain;

import com.comet.opik.api.DatasetEvaluator;
import com.comet.opik.api.DatasetEvaluator.DatasetEvaluatorBatchRequest;
import com.comet.opik.api.DatasetEvaluator.DatasetEvaluatorPage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(DatasetEvaluatorServiceImpl.class)
public interface DatasetEvaluatorService {

    List<DatasetEvaluator> createBatch(UUID datasetId, DatasetEvaluatorBatchRequest request);

    DatasetEvaluatorPage getByDatasetId(UUID datasetId, int page, int size);

    void deleteBatch(UUID datasetId, Set<UUID> ids);

    void deleteByDatasetId(UUID datasetId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetEvaluatorServiceImpl implements DatasetEvaluatorService {

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;

    @Override
    public List<DatasetEvaluator> createBatch(@NonNull UUID datasetId, @NonNull DatasetEvaluatorBatchRequest request) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Creating '{}' dataset evaluators for dataset '{}' in workspace '{}'",
                request.evaluators().size(), datasetId, workspaceId);

        List<DatasetEvaluator> evaluators = request.evaluators().stream()
                .map(create -> {
                    UUID id = idGenerator.generateId();
                    IdGenerator.validateVersion(id, "DatasetEvaluator");
                    return DatasetEvaluator.builder()
                            .id(id)
                            .datasetId(datasetId)
                            .name(create.name())
                            .metricType(create.metricType())
                            .metricConfig(create.metricConfig())
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();
                })
                .toList();

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetEvaluatorDAO.class);
            dao.batchInsert(evaluators, workspaceId);

            Set<UUID> ids = evaluators.stream().map(DatasetEvaluator::id).collect(java.util.stream.Collectors.toSet());
            List<DatasetEvaluator> created = dao.findByIds(workspaceId, ids);

            log.info("Created '{}' dataset evaluators for dataset '{}' in workspace '{}'",
                    created.size(), datasetId, workspaceId);
            return created;
        });
    }

    @Override
    public DatasetEvaluatorPage getByDatasetId(@NonNull UUID datasetId, int page, int size) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting dataset evaluators for dataset '{}' in workspace '{}', page='{}', size='{}'",
                datasetId, workspaceId, page, size);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetEvaluatorDAO.class);

            int offset = page * size;
            List<DatasetEvaluator> evaluators = dao.findByDatasetId(workspaceId, datasetId, size, offset);
            long total = dao.countByDatasetId(workspaceId, datasetId);

            return DatasetEvaluatorPage.builder()
                    .content(evaluators)
                    .page(page)
                    .size(evaluators.size())
                    .total(total)
                    .build();
        });
    }

    @Override
    public void deleteBatch(@NonNull UUID datasetId, @NonNull Set<UUID> ids) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting '{}' dataset evaluators for dataset '{}' in workspace '{}'",
                ids.size(), datasetId, workspaceId);

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetEvaluatorDAO.class);

            long matchingCount = dao.countByIdsAndDatasetId(workspaceId, datasetId, ids);
            if (matchingCount != ids.size()) {
                throw new BadRequestException(
                        "Some evaluator IDs do not belong to dataset '%s'. Requested: %d, found: %d"
                                .formatted(datasetId, ids.size(), matchingCount));
            }

            int deleted = dao.deleteByIdsAndDatasetId(workspaceId, datasetId, ids);
            log.info("Deleted '{}' dataset evaluators for dataset '{}' in workspace '{}'",
                    deleted, datasetId, workspaceId);
            return null;
        });
    }

    @Override
    public void deleteByDatasetId(@NonNull UUID datasetId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting all dataset evaluators for dataset '{}' in workspace '{}'", datasetId, workspaceId);

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetEvaluatorDAO.class);
            int deleted = dao.deleteByDatasetId(workspaceId, datasetId);
            log.info("Deleted '{}' dataset evaluators for dataset '{}' in workspace '{}'", deleted, datasetId,
                    workspaceId);
            return null;
        });
    }
}
