package com.comet.opik.domain;

import com.comet.opik.api.DatasetExportJob;
import com.comet.opik.api.DatasetExportStatus;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(DatasetExportJobServiceImpl.class)
public interface DatasetExportJobService {

    Mono<DatasetExportJob> createJob(UUID datasetId, Duration ttl);

    Mono<List<DatasetExportJob>> findInProgressJobs(UUID datasetId);

    Mono<DatasetExportJob> getJob(UUID jobId);

    Mono<Void> updateJobStatus(UUID jobId, DatasetExportStatus status, String filePath, String errorMessage);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class DatasetExportJobServiceImpl implements DatasetExportJobService {

    private static final Set<DatasetExportStatus> IN_PROGRESS_STATUSES = Set.of(
            DatasetExportStatus.PENDING,
            DatasetExportStatus.PROCESSING);

    private final @NonNull DatasetExportJobDAO exportJobDAO;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;

    @Override
    public Mono<DatasetExportJob> createJob(@NonNull UUID datasetId, @NonNull Duration ttl) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return Mono.fromCallable(() -> {
                UUID jobId = idGenerator.generateId();
                Instant now = Instant.now();
                Instant expiresAt = now.plus(ttl);

                DatasetExportJob newJob = DatasetExportJob.builder()
                        .id(jobId)
                        .datasetId(datasetId)
                        .status(DatasetExportStatus.PENDING)
                        .createdAt(now)
                        .lastUpdatedAt(now)
                        .expiresAt(expiresAt)
                        .createdBy(userName)
                        .build();

                template.inTransaction(WRITE, handle -> {
                    var dao = handle.attach(DatasetExportJobDAO.class);
                    dao.save(newJob, workspaceId);
                    return null;
                });

                log.info("Created export job: '{}' for dataset: '{}' in workspace: '{}'", jobId, datasetId,
                        workspaceId);

                return newJob;
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }

    @Override
    public Mono<List<DatasetExportJob>> findInProgressJobs(@NonNull UUID datasetId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(DatasetExportJobDAO.class);
                List<DatasetExportJob> existingJobs = dao.findInProgressByDataset(workspaceId, datasetId,
                        IN_PROGRESS_STATUSES);

                if (!existingJobs.isEmpty()) {
                    log.info("Found '{}' existing in-progress export job(s) for dataset: '{}'", existingJobs.size(),
                            datasetId);
                }

                return existingJobs;
            })).subscribeOn(Schedulers.boundedElastic());
        });
    }

    @Override
    public Mono<DatasetExportJob> getJob(@NonNull UUID jobId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(DatasetExportJobDAO.class);
                return dao.findById(workspaceId, jobId)
                        .orElseThrow(() -> new NotFoundException("Export job not found: '%s'".formatted(jobId)));
            })).subscribeOn(Schedulers.boundedElastic());
        });
    }

    @Override
    public Mono<Void> updateJobStatus(@NonNull UUID jobId, @NonNull DatasetExportStatus status, String filePath,
            String errorMessage) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> {
                template.inTransaction(WRITE, handle -> {
                    var dao = handle.attach(DatasetExportJobDAO.class);
                    int updated = dao.update(workspaceId, jobId, status, filePath, errorMessage);

                    if (updated == 0) {
                        throw new NotFoundException("Export job not found: '%s'".formatted(jobId));
                    }

                    return null;
                });

                log.info("Updated export job: '{}' to status: '{}'", jobId, status);
                return null;
            }).subscribeOn(Schedulers.boundedElastic()).then();
        });
    }
}
