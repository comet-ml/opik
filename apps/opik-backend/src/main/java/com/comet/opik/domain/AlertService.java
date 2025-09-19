package com.comet.opik.domain;

import com.comet.opik.api.Alert;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(AlertServiceImpl.class)
public interface AlertService {

    Alert create(@NonNull Alert alert);

    Optional<Alert> getById(@NonNull UUID id);

    Alert update(@NonNull UUID id, @NonNull Alert alert);

    void delete(@NonNull UUID id);

    List<Alert> findAlerts(int page, int size, String name, String conditionType, UUID projectId, String sorting);

    long count(String name, String conditionType, UUID projectId);

    List<Alert> findByProjectId(@NonNull UUID projectId);

    Alert.AlertPage getAlerts(int page, int size, UUID projectId);
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

    @Override
    public Optional<Alert> getById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AlertDAO.class);
            return dao.fetch(id, workspaceId);
        });
    }

    @Override
    public Alert update(@NonNull UUID id, @NonNull Alert alert) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AlertDAO.class);

            // Check if alert exists
            dao.fetch(id, workspaceId)
                    .orElseThrow(() -> new NotFoundException(ALERT_NOT_FOUND));

            // Update the alert
            dao.update(id, workspaceId, alert.name(), alert.description(),
                    alert.conditionType(), alert.thresholdValue(),
                    alert.projectId(), userName);

            // Return the updated alert
            return dao.fetch(id, workspaceId)
                    .orElseThrow(() -> new NotFoundException(ALERT_NOT_FOUND));
        });
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AlertDAO.class);

            // Check if alert exists
            dao.fetch(id, workspaceId)
                    .orElseThrow(() -> new NotFoundException(ALERT_NOT_FOUND));

            dao.delete(id, workspaceId);

            log.info("Alert deleted with id '{}' on workspace_id '{}'", id, workspaceId);
            return null;
        });
    }

    @Override
    public List<Alert> findAlerts(int page, int size, String name, String conditionType, UUID projectId,
            String sorting) {
        String workspaceId = requestContext.get().getWorkspaceId();
        int offset = (page - 1) * size;

        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AlertDAO.class);
            return dao.find(size, offset, workspaceId, name, conditionType, projectId, sorting);
        });
    }

    @Override
    public long count(String name, String conditionType, UUID projectId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AlertDAO.class);
            return dao.findCount(workspaceId, name, conditionType, projectId);
        });
    }

    @Override
    public List<Alert> findByProjectId(@NonNull UUID projectId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AlertDAO.class);
            return dao.findByProjectId(projectId, workspaceId);
        });
    }

    private Alert saveAlert(String workspaceId, Alert alert) {
        log.info("Creating alert with id '{}', name '{}'", alert.id(), alert.name());

        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AlertDAO.class);
            return dao.save(workspaceId, alert);
        });
    }

    @Override
    public Alert.AlertPage getAlerts(int page, int size, UUID projectId) {
        var alerts = findAlerts(page, size, null, null, projectId, null);
        var totalCount = count(null, null, projectId);

        return new Alert.AlertPage(page, size, totalCount, alerts, List.of("name", "created_at", "condition_type"));
    }

    private EntityAlreadyExistsException newAlertConflict() {
        return new EntityAlreadyExistsException(new ErrorMessage(List.of(ALERT_ALREADY_EXISTS)));
    }
}
