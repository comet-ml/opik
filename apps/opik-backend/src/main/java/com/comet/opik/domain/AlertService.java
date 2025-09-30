package com.comet.opik.domain;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.Webhook;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.jdbi.v3.core.Handle;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(AlertServiceImpl.class)
public interface AlertService {

    UUID create(Alert alert);

    void update(UUID id, Alert alert);

    Alert getById(UUID id);

    void deleteBatch(Set<UUID> ids);
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
    public UUID create(@NonNull Alert alert) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        var newAlert = prepareAlert(alert, userName);

        return EntityConstraintHandler
                .handle(() -> saveAlert(newAlert, workspaceId))
                .withError(this::newAlertConflict);
    }

    @Override
    public void update(@NonNull UUID id, @NonNull Alert alert) {
        if (id.compareTo(alert.id()) != 0) {
            throw new BadRequestException("Payload alert ID must match the path ID");
        }

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        // Ensure the alert exists, will throw NotFoundException if not
        getById(id);

        // Prepare new updated alert with the same ID
        var newAlert = prepareAlert(alert, userName);

        transactionTemplate.inTransaction(WRITE, handle -> {
            // Delete existing alert and all its related entities (triggers, trigger configs, webhook)
            deleteBatch(handle, Set.of(id));

            // Save updated alert
            saveAlert(handle, newAlert, workspaceId);

            return null;
        });
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

    @Override
    public void deleteBatch(Set<UUID> ids) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            deleteBatch(handle, ids);
            return null;
        });
    }

    private void deleteBatch(Handle handle, Set<UUID> ids) {
        String workspaceId = requestContext.get().getWorkspaceId();
        AlertDAO alertDAO = handle.attach(AlertDAO.class);
        alertDAO.delete(ids, workspaceId);
    }

    private UUID saveAlert(Alert alert, String workspaceId) {
        return transactionTemplate.inTransaction(WRITE, handle -> saveAlert(handle, alert, workspaceId));
    }

    private UUID saveAlert(Handle handle, Alert alert, String workspaceId) {

        AlertDAO alertDAO = handle.attach(AlertDAO.class);
        alertDAO.save(workspaceId, alert, alert.webhook().id());

        WebhookDAO webhookDAO = handle.attach(WebhookDAO.class);
        webhookDAO.save(workspaceId, alert.webhook());

        // Save triggers and their configs
        if (CollectionUtils.isNotEmpty(alert.triggers())) {
            AlertTriggerDAO alertTriggerDAO = handle.attach(AlertTriggerDAO.class);
            alertTriggerDAO.saveBatch(alert.triggers());

            List<AlertTriggerConfig> triggerConfigs = alert.triggers().stream()
                    .filter(trigger -> CollectionUtils.isNotEmpty(trigger.triggerConfigs()))
                    .flatMap(trigger -> trigger.triggerConfigs().stream())
                    .toList();

            if (CollectionUtils.isNotEmpty(triggerConfigs)) {
                AlertTriggerConfigDAO alertTriggerConfigDAO = handle.attach(AlertTriggerConfigDAO.class);
                alertTriggerConfigDAO.saveBatch(triggerConfigs);
            }
        }

        return alert.id();
    }

    private EntityAlreadyExistsException newAlertConflict() {
        return new EntityAlreadyExistsException(new ErrorMessage(HttpStatus.SC_CONFLICT, ALERT_ALREADY_EXISTS));
    }

    private Alert prepareAlert(Alert alert, String userName) {
        UUID id = alert.id() == null ? idGenerator.generateId() : alert.id();
        IdGenerator.validateVersion(id, "Alert");

        UUID webhookId = alert.webhook().id() == null ? idGenerator.generateId() : alert.webhook().id();
        IdGenerator.validateVersion(webhookId, "Webhook");

        Webhook webhook = alert.webhook()
                .toBuilder()
                .id(webhookId)
                .name("Webhook for alert " + alert.id()) // Not used by FE
                .createdBy(Optional.ofNullable(alert.createdBy()).orElse(userName))
                .lastUpdatedBy(userName)
                .build();

        // Prepare triggers with generated IDs
        List<AlertTrigger> preparedTriggers = null;
        if (alert.triggers() != null) {
            preparedTriggers = alert.triggers().stream()
                    .map(trigger -> prepareTrigger(trigger, userName, id))
                    .toList();
        }

        return alert.toBuilder()
                .id(id)
                .enabled(alert.enabled() != null ? alert.enabled() : true) // Set default to true only when not explicitly provided
                .webhook(webhook)
                .triggers(preparedTriggers)
                .createdBy(Optional.ofNullable(alert.createdBy()).orElse(userName))
                .lastUpdatedBy(userName)
                .build();
    }

    private AlertTrigger prepareTrigger(AlertTrigger trigger, String userName, UUID alertId) {
        UUID triggerId = trigger.id() == null ? idGenerator.generateId() : trigger.id();
        IdGenerator.validateVersion(triggerId, "Alert Trigger");

        List<AlertTriggerConfig> preparedConfigs = null;
        if (trigger.triggerConfigs() != null) {
            preparedConfigs = trigger.triggerConfigs().stream()
                    .map(config -> prepareTriggerConfig(config, userName, triggerId))
                    .toList();
        }

        return trigger.toBuilder()
                .id(triggerId)
                .alertId(alertId)
                .triggerConfigs(preparedConfigs)
                .createdBy(Optional.ofNullable(trigger.createdBy()).orElse(userName))
                .build();
    }

    private AlertTriggerConfig prepareTriggerConfig(AlertTriggerConfig config, String userName, UUID triggerId) {
        UUID triggerConfigId = config.id() == null ? idGenerator.generateId() : config.id();
        IdGenerator.validateVersion(triggerConfigId, "Alert Trigger Config");

        return config.toBuilder()
                .id(triggerConfigId)
                .alertTriggerId(triggerId)
                .createdBy(Optional.ofNullable(config.createdBy()).orElse(userName))
                .lastUpdatedBy(userName)
                .build();
    }
}