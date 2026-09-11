package com.comet.opik.domain;

import com.comet.opik.api.ReportFailure;
import com.comet.opik.api.ReportFailureType;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(ReportFailureServiceImpl.class)
public interface ReportFailureService {

    UUID create(ReportFailure failure);

    ReportFailure.ReportFailurePage find(ReportFailureType type, UUID projectId, int page, int size);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ReportFailureServiceImpl implements ReportFailureService {

    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull ProjectService projectService;

    @Override
    public UUID create(@NonNull ReportFailure failure) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        UUID id = idGenerator.generateId();

        // type is a validated enum (unknown values are rejected at deserialization with a 400), so no manual
        // check is needed. The entity is a project — reject failures for a non-existent one so they can't
        // linger as orphan rows that a later job query surfaces.
        projectService.validateProjectIdExists(failure.projectId(), workspaceId);

        log.info("Recording report failure type '{}' project '{}' in workspace '{}': '{}'",
                failure.type().getValue(), failure.projectId(), workspaceId, failure.reason());

        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(ReportFailureDAO.class).insert(id, workspaceId, failure.type().getValue(),
                    failure.projectId(), failure.reason(), failure.detail(), userName);
            return null;
        });
        return id;
    }

    @Override
    public ReportFailure.ReportFailurePage find(@NonNull ReportFailureType type, @NonNull UUID projectId, int page,
            int size) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            ReportFailureDAO dao = handle.attach(ReportFailureDAO.class);
            List<ReportFailure> content = dao.find(workspaceId, type.getValue(), projectId, size, (page - 1) * size);
            long total = dao.count(workspaceId, type.getValue(), projectId);
            return ReportFailure.ReportFailurePage.builder()
                    .page(page)
                    .size(size)
                    .total(total)
                    .content(content)
                    .build();
        });
    }
}
