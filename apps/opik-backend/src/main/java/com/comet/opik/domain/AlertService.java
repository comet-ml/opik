package com.comet.opik.domain;

import com.comet.opik.api.Alert;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(AlertServiceImpl.class)
public interface AlertService {

    Alert create(Alert alert);

    Alert getById(UUID id);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AlertServiceImpl implements AlertService {

    private static final String ALERT_ALREADY_EXISTS = "Alert already exists";
    private static final String ALERT_NOT_FOUND = "Alert not found";

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;

    @Override
    public Alert create(@NonNull Alert alert) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        var newAlert = prepareAlert(alert, userName);

        Alert createdAlert = EntityConstraintHandler
                .handle(() -> saveAlert(newAlert, workspaceId))
                .withError(this::newAlertConflict);

        return createdAlert;
    }

    @Override
    public Alert getById(UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            AlertDAO alertDAO = handle.attach(AlertDAO.class);

            Alert alert = alertDAO.findById(id, workspaceId);

            if (alert == null) {
                throw new NotFoundException(ALERT_NOT_FOUND);
            }

            return alert;
        });
    }

    private Alert saveAlert(Alert alert, String workspaceId) {

        transactionTemplate.inTransaction(WRITE, handle -> {
            AlertDAO alertDAO = handle.attach(AlertDAO.class);

            alertDAO.save(workspaceId, alert);

            return null;
        });

        return getById(alert.id());
    }

    private EntityAlreadyExistsException newAlertConflict() {
        return new EntityAlreadyExistsException(new ErrorMessage(HttpStatus.SC_CONFLICT, ALERT_ALREADY_EXISTS));
    }

    private Alert prepareAlert(Alert alert, String userName) {
        UUID id = alert.id() == null ? idGenerator.generateId() : alert.id();
        IdGenerator.validateVersion(id, "Alert");

        log.debug("Preparing Alert with id '{}', name '{}'",
                id, alert.name());

        return alert.toBuilder()
                .id(id)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .enabled(alert.enabled() != null ? alert.enabled() : true)
                .build();
    }
}