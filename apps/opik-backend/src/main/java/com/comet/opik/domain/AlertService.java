package com.comet.opik.domain;

import com.comet.opik.api.Alert;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(AlertServiceImpl.class)
public interface AlertService {

    Alert create(@NonNull Alert alert);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AlertServiceImpl implements AlertService {

    private static final String ALERT_ALREADY_EXISTS = "Alert already exists";

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;

    @Override
    public Alert create(@NonNull Alert alert) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        var newAlert = alert.toBuilder()
                .id(alert.id() == null ? idGenerator.generateId() : alert.id())
                .workspaceId(workspaceId)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .build();

        IdGenerator.validateVersion(newAlert.id(), "alert");

        Alert createdAlert = EntityConstraintHandler
                .handle(() -> saveAlert(workspaceId, newAlert))
                .withError(this::newAlertConflict);

        log.info("Alert created with id '{}', name '{}', on workspace_id '{}'",
                createdAlert.id(), createdAlert.name(), workspaceId);

        return createdAlert;
    }

    private Alert saveAlert(String workspaceId, Alert alert) {
        log.info("Creating alert with id '{}', name '{}'", alert.id(), alert.name());

        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AlertDAO.class);
            return dao.save(workspaceId, alert);
        });
    }

    private EntityAlreadyExistsException newAlertConflict() {
        return new EntityAlreadyExistsException(new ErrorMessage(List.of(ALERT_ALREADY_EXISTS)));
    }
}