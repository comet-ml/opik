package com.comet.opik.domain;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.Webhook;
import com.comet.opik.api.WebhookTestResult;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.resources.v1.events.webhooks.WebhookHttpClient;
import com.comet.opik.api.sorting.SortingFactoryAlerts;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.RetryUtils;
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
import org.jdbi.v3.core.Handle;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@ImplementedBy(AlertServiceImpl.class)
public interface AlertService {

    UUID create(Alert alert);

    void update(UUID id, Alert alert);

    Alert.AlertPage find(int page, int size, List<SortingField> sortingFields, List<? extends Filter> filters);

    Alert getById(UUID id);

    List<Alert> findAllByWorkspace(String workspaceId);

    Alert getByIdAndWorkspace(UUID id, String workspaceId);

    void deleteBatch(Set<UUID> ids);

    WebhookTestResult testWebhook(Alert alert);
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
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull SortingFactoryAlerts sortingFactory;
    private final @NonNull WebhookHttpClient webhookHttpClient;

    @Override
    public UUID create(@NonNull Alert alert) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        var newAlert = prepareAlert(alert, userName);

        return EntityConstraintHandler
                .handle(() -> saveAlert(newAlert, workspaceId))
                .withError(this::newAlertConflict);
    }

    //TODO: Now endpoint is used to update alert/webhook and create/update/delete triggers and trigger configs.
    // Should be split into separate endpoints in the future
    @Override
    public void update(@NonNull UUID id, @NonNull Alert alert) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        // Ensure the alert exists, will throw NotFoundException if not
        var existingAlert = getById(id);
        alert = alert.toBuilder()
                .createdBy(existingAlert.createdBy())
                .createdAt(existingAlert.createdAt())
                .build();

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
    public Alert.AlertPage find(int page, int size, List<SortingField> sortingFields, List<? extends Filter> filters) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String sortingFieldsSql = sortingQueryBuilder.toOrderBySql(sortingFields);

        String filtersSQL = Optional.ofNullable(filters)
                .flatMap(f -> filterQueryBuilder.toAnalyticsDbFilters(f, FilterStrategy.ALERT))
                .orElse(null);

        Map<String, Object> filterMapping = Optional.ofNullable(filters)
                .map(filterQueryBuilder::toStateSQLMapping)
                .orElse(Map.of());

        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            AlertDAO alertDAO = handle.attach(AlertDAO.class);

            long total = alertDAO.count(workspaceId, filtersSQL, filterMapping);

            var offset = (page - 1) * size;

            List<Alert> content = alertDAO.find(workspaceId, offset, size, sortingFieldsSql, filtersSQL,
                    filterMapping);

            return Alert.AlertPage.builder()
                    .page(page)
                    .size(content.size())
                    .content(content)
                    .total(total)
                    .sortableBy(sortingFactory.getSortableFields())
                    .build();
        });
    }

    @Override
    public Alert getById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();
        return getByIdAndWorkspace(id, workspaceId);
    }

    @Override
    @Cacheable(name = "alert_find_all_per_workspace", key = "$workspaceId", returnType = Alert.class, wrapperType = List.class)
    public List<Alert> findAllByWorkspace(@NonNull String workspaceId) {
        log.info("Fetching all alerts for workspace '{}'", workspaceId);
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            AlertDAO alertDAO = handle.attach(AlertDAO.class);

            return alertDAO.find(workspaceId, 0, 1000, null, null,
                    Map.of());
        });
    }

    @Override
    public Alert getByIdAndWorkspace(@NonNull UUID id, @NonNull String workspaceId) {
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
    public void deleteBatch(@NonNull Set<UUID> ids) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            deleteBatch(handle, ids);
            return null;
        });
    }

    @Override
    public WebhookTestResult testWebhook(@NonNull Alert alert) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        var event = mapAlertToWebhookEvent(alert, workspaceId, userName);
        String requestBody = JsonUtils.writeValueAsString(event);

        return Mono.defer(() -> webhookHttpClient.sendWebhook(event))
                .contextWrite(ctx -> setRequestContext(ctx, userName, workspaceId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(response -> {
                    log.info("Successfully sent webhook: id='{}', type='{}', url='{}', statusCode='{}'",
                            event.getId(), event.getEventType(), event.getUrl(), response.getStatus());

                    return WebhookTestResult.builder()
                            .status(WebhookTestResult.Status.SUCCESS)
                            .statusCode(response.getStatus())
                            .requestBody(requestBody)
                            .errorMessage(null)
                            .build();
                })
                .onErrorResume(throwable -> {
                    log.error("Failed to send webhook: id='{}', type='{}', url='{}', error='{}'",
                            event.getId(), event.getEventType(), event.getUrl(), throwable.getMessage(), throwable);

                    int statusCode = (throwable instanceof RetryUtils.RetryableHttpException rhe)
                            ? rhe.getStatusCode()
                            : 0;

                    return Mono.just(WebhookTestResult.builder()
                            .status(WebhookTestResult.Status.FAILURE)
                            .statusCode(statusCode)
                            .requestBody(requestBody)
                            .errorMessage(throwable.getMessage())
                            .build());
                })
                .block();
    }

    private WebhookEvent<Map<String, Object>> mapAlertToWebhookEvent(Alert alert, String workspaceId, String userName) {
        String eventId = idGenerator.generateId().toString();
        var eventType = alert.triggers().isEmpty()
                ? AlertEventType.TRACE_ERRORS
                : alert.triggers().getFirst().eventType();
        Set<String> eventIds = Set.of(idGenerator.generateId().toString()); // Dummy event ID for test

        Map<String, Object> payload = Map.of(
                "alertId", alert.id().toString(),
                "alertName", alert.name(),
                "eventType", eventType.getValue(),
                "eventIds", eventIds,
                "eventCount", eventIds.size(),
                "aggregationType", "consolidated",
                "message", String.format("Alert '%s': %d %s events aggregated",
                        alert.name(), eventIds.size(), eventType.getValue()));

        return WebhookEvent.<Map<String, Object>>builder()
                .id(eventId)
                .url(alert.webhook().url())
                .eventType(eventType)
                .alertId(alert.id())
                .payload(payload)
                .headers(Optional.ofNullable(alert.webhook().headers()).orElse(Map.of()))
                .maxRetries(1)
                .workspaceId(workspaceId)
                .userName(userName)
                .createdAt(Instant.now())
                .build();
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
                .createdAt(alert.createdAt()) // will be null for new alert, and not null for update
                .lastUpdatedBy(userName)
                .build();

        // Prepare triggers with generated IDs
        List<AlertTrigger> preparedTriggers = null;
        if (alert.triggers() != null) {
            preparedTriggers = alert.triggers().stream()
                    .map(trigger -> prepareTrigger(trigger, userName, id, alert))
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

    private AlertTrigger prepareTrigger(AlertTrigger trigger, String userName, UUID alertId, Alert alert) {
        UUID triggerId = trigger.id() == null ? idGenerator.generateId() : trigger.id();
        IdGenerator.validateVersion(triggerId, "Alert Trigger");

        List<AlertTriggerConfig> preparedConfigs = null;
        if (trigger.triggerConfigs() != null) {
            preparedConfigs = trigger.triggerConfigs().stream()
                    .map(config -> prepareTriggerConfig(config, userName, triggerId, alert))
                    .toList();
        }

        return trigger.toBuilder()
                .id(triggerId)
                .alertId(alertId)
                .triggerConfigs(preparedConfigs)
                .createdBy(Optional.ofNullable(trigger.createdBy()).orElse(userName))
                .createdAt(alert.createdAt()) // will be null for new alert, and not null for update
                .build();
    }

    private AlertTriggerConfig prepareTriggerConfig(AlertTriggerConfig config, String userName, UUID triggerId,
            Alert alert) {
        UUID triggerConfigId = config.id() == null ? idGenerator.generateId() : config.id();
        IdGenerator.validateVersion(triggerConfigId, "Alert Trigger Config");

        return config.toBuilder()
                .id(triggerConfigId)
                .alertTriggerId(triggerId)
                .createdBy(Optional.ofNullable(config.createdBy()).orElse(userName))
                .createdAt(alert.createdAt()) // will be null for new alert, and not null for update
                .lastUpdatedBy(userName)
                .build();
    }
}