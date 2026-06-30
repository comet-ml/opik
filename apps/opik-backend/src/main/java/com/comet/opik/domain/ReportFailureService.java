package com.comet.opik.domain;

import com.comet.opik.api.ReportFailure;
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

    ReportFailure.ReportFailurePage find(String type, UUID entityId, int page, int size);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ReportFailureServiceImpl implements ReportFailureService {

    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Provider<RequestContext> requestContext;

    @Override
    public UUID create(@NonNull ReportFailure failure) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        UUID id = idGenerator.generateId();

        log.info("Recording report failure type '{}' entity '{}' in workspace '{}': '{}'",
                failure.type(), failure.entityId(), workspaceId, failure.reason());

        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(ReportFailureDAO.class).insert(id, workspaceId, failure.type(), failure.entityId(),
                    failure.reason(), failure.detail(), userName);
            return null;
        });
        return id;
    }

    @Override
    public ReportFailure.ReportFailurePage find(@NonNull String type, @NonNull UUID entityId, int page, int size) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            ReportFailureDAO dao = handle.attach(ReportFailureDAO.class);
            List<ReportFailure> content = dao.find(workspaceId, type, entityId, size, (page - 1) * size);
            long total = dao.count(workspaceId, type, entityId);
            return ReportFailure.ReportFailurePage.builder()
                    .page(page)
                    .size(size)
                    .total(total)
                    .content(content)
                    .build();
        });
    }
}
