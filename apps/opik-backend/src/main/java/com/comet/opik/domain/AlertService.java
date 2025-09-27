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
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static java.util.stream.Collectors.groupingBy;

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
            AlertTriggerDAO alertTriggerDAO = handle.attach(AlertTriggerDAO.class);
            AlertTriggerConfigDAO alertTriggerConfigDAO = handle.attach(AlertTriggerConfigDAO.class);

            Alert alert = alertDAO.findById(id, workspaceId);

            if (alert == null) {
                throw new NotFoundException(ALERT_NOT_FOUND);
            }

            // Fetch triggers and their configs
            List<AlertTrigger> triggers = alertTriggerDAO.findByAlertId(id);

            List<AlertTrigger> triggersWithConfigs = null;

            if (CollectionUtils.isNotEmpty(triggers)) {
                var triggerConfigMap = findAlertTriggerConfigMap(alertTriggerConfigDAO, triggers);

                triggersWithConfigs = triggers.stream()
                        .map(trigger -> trigger.toBuilder()
                                .triggerConfigs(triggerConfigMap.get(trigger.id()))
                                .build())
                        .toList();
            }

            return alert.toBuilder()
                    .triggers(triggersWithConfigs)
                    .build();
        });
    }

    private Alert saveAlert(Alert alert, String workspaceId) {

        transactionTemplate.inTransaction(WRITE, handle -> {
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

        Webhook webhook = alert.webhook()
                .toBuilder()
                .id(idGenerator.generateId())
                .name("Webhook for alert " + alert.id()) // Not used by FE
                .createdBy(userName)
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
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();
    }

    private AlertTrigger prepareTrigger(AlertTrigger trigger, String userName, UUID alertId) {
        UUID triggerId = idGenerator.generateId();

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
                .createdBy(userName)
                .build();
    }

    private AlertTriggerConfig prepareTriggerConfig(AlertTriggerConfig config, String userName, UUID triggerId) {

        return config.toBuilder()
                .id(idGenerator.generateId())
                .alertTriggerId(triggerId)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();
    }

    private Map<UUID, List<AlertTriggerConfig>> findAlertTriggerConfigMap(AlertTriggerConfigDAO alertTriggerConfigDAO,
            List<AlertTrigger> triggers) {
        var triggerIds = triggers.stream().map(AlertTrigger::id).toList();
        var configs = alertTriggerConfigDAO.findByAlertTriggerIds(triggerIds);

        return configs.stream().collect(groupingBy(AlertTriggerConfig::alertTriggerId));
    }
}