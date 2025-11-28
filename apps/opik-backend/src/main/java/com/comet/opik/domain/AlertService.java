package com.comet.opik.domain;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertType;
import com.comet.opik.api.Webhook;
import com.comet.opik.api.WebhookExamples;
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
import com.comet.opik.utils.RetryUtils;
import com.fasterxml.uuid.Generators;
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
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.jdbi.v3.core.Handle;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.v1.events.webhooks.pagerduty.PagerDutyWebhookPayloadMapper.ROUTING_KEY_METADATA_KEY;
import static com.comet.opik.api.resources.v1.events.webhooks.slack.AlertPayloadAdapter.deserializeEventPayload;
import static com.comet.opik.api.resources.v1.events.webhooks.slack.AlertPayloadAdapter.prepareWebhookPayload;
import static com.comet.opik.api.resources.v1.events.webhooks.slack.AlertPayloadAdapter.webhookEventPayloadPerType;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@ImplementedBy(AlertServiceImpl.class)
public interface AlertService {

    UUID create(Alert alert);

    void update(UUID id, Alert alert);

    Alert.AlertPage find(int page, int size, List<SortingField> sortingFields, List<? extends Filter> filters);

    Alert getById(UUID id);

    List<Alert> findAllByWorkspaceAndEventTypes(String workspaceId, Set<AlertEventType> eventTypes);

    Alert getByIdAndWorkspace(UUID id, String workspaceId);

    void deleteBatch(Set<UUID> ids);

    WebhookTestResult testWebhook(Alert alert);

    WebhookExamples getWebhookExamples(AlertType alertType);
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

    private final static EnumMap<AlertEventType, String> TEST_PAYLOAD = new EnumMap<>(Map.of(
            AlertEventType.TRACE_ERRORS,
            """
                    {
                      "event_type": "TRACE_ERRORS",
                      "metric_name": "trace:errors",
                      "metric_value": "15",
                      "threshold": "10",
                      "window_seconds": "3600",
                      "project_ids": "0198ec68-6e06-7253-a20b-d35c9252b9ba,0198ec68-6e06-7253-a20b-d35c9252b9bb",
                      "project_names": "Demo Project,Default Project"
                    }
                    """,
            AlertEventType.TRACE_FEEDBACK_SCORE,
            """
                    {
                      "event_type": "TRACE_FEEDBACK_SCORE",
                      "metric_name": "trace:feedback_score",
                      "metric_value": "0.7500",
                      "threshold": "0.5",
                      "window_seconds": "3600",
                      "project_ids": "0198ec68-6e06-7253-a20b-d35c9252b9ba,0198ec68-6e06-7253-a20b-d35c9252b9bb",
                      "project_names": "Demo Project,Default Project"
                    }
                    """,
            AlertEventType.TRACE_THREAD_FEEDBACK_SCORE,
            """
                    {
                      "event_type": "TRACE_THREAD_FEEDBACK_SCORE",
                      "metric_name": "trace_thread:feedback_score",
                      "metric_value": "0.7500",
                      "threshold": "0.5",
                      "window_seconds": "3600",
                      "project_ids": "0198ec68-6e06-7253-a20b-d35c9252b9ba,0198ec68-6e06-7253-a20b-d35c9252b9bb",
                      "project_names": "Demo Project,Default Project"
                    }
                    """,
            AlertEventType.PROMPT_CREATED,
            """
                    {
                      "id": "0198c90a-46ca-70e2-944d-cac10720ab66",
                      "name": "Opik SDK Assistant - System Prompt",
                      "description": "System prompt for Opik SDK assistant to help users with technical questions",
                      "tags": ["system", "assistant"],
                      "created_at": "2025-08-27T10:00:00Z",
                      "created_by": "test-user",
                      "last_updated_at": "2025-08-27T10:00:00Z",
                      "last_updated_by": "test-user"
                    }
                    """,
            AlertEventType.PROMPT_COMMITTED,
            """
                    {
                      "id": "0198c90a-46c6-78ef-90d9-62ed986afb80",
                      "prompt_id": "0198c90a-46ca-70e2-944d-cac10720ab66",
                      "commit": "986afb80",
                      "template": "You are an Opik expert and know how to explain Comet SDK concepts in simple terms. Keep the answers short and don't try to make up answers that you don't know.",
                      "type": "mustache",
                      "metadata": {
                        "version": "1.0",
                        "model": "gpt-4"
                      },
                      "created_at": "2025-08-27T10:00:00Z",
                      "created_by": "test-user"
                    }
                    """,
            AlertEventType.PROMPT_DELETED,
            """
                    [
                        {
                          "id": "0198c90a-46ca-70e2-944d-cac10720ab66",
                          "name": "Old System Prompt",
                          "description": "Deprecated system prompt that is no longer in use",
                          "tags": ["deprecated"],
                          "created_at": "2025-07-15T10:00:00Z",
                          "created_by": "test-user",
                          "last_updated_at": "2025-08-27T10:00:00Z",
                          "last_updated_by": "test-user",
                          "latest_version": {
                              "id": "0198c90a-46c6-78ef-90d9-62ed986afb80",
                              "commit": "986afb80",
                              "template": "You are an Opik expert and know how to explain Comet SDK concepts in simple terms. Keep the answers short and don't try to make up answers that you don't know.",
                              "type": "mustache",
                              "created_at": "2025-08-27T10:00:00Z",
                              "created_by": "test-user"
                          }
                        }
                    ]
                    """,
            AlertEventType.TRACE_GUARDRAILS_TRIGGERED,
            """
                    [
                        {
                          "id": "0198ec7e-e999-7537-bbbb-fc5db24face8",
                          "entity_id": "0198ec7e-e844-7537-aaaa-fc5db24face7",
                          "project_id": "0198ec68-6e06-7253-a20b-d35c9252b9ba",
                          "project_name": "Demo Project",
                          "name": "PII",
                          "result": "failed",
                          "details": {
                            "detected_entities": ["EMAIL", "PHONE_NUMBER"],
                            "message": "PII detected in response: email address and phone number"
                          }
                        }
                    ]
                    """,
            AlertEventType.EXPERIMENT_FINISHED,
            """
                    [
                        {
                                "id": "0198c90e-3884-7fe6-9236-168acd26d4bb",
                                "name": "opik-assistant-v1",
                                "dataset_id": "0198c909-9294-7d3a-a3c2-7511f46a9ef0",
                                "metadata": {
                                    "model": "gpt-4o-mini",
                                    "prompts": [
                                        "You are an instructor for technical executives that want to extract value of AI models.\\n        If you know the answer to the question, respond by stating that it is possible to do what is being asked,\\n        but without going into technical details on how to do it.\\n        Make sure you include in your answer:\\n        - A description of the lifecycle of a machine learning model\\n        - Where in this lifecycle the current question is relevant\\n        - The business benefits of implementing the provided answer\\n        - An estimation of the time and cost of implementing the provided answer"
                                    ]
                                },
                                "type": "regular"
                            }
                    ]
                    """,
            AlertEventType.TRACE_COST,
            """
                    {
                      "event_type": "TRACE_COST",
                      "metric_name": "trace:cost",
                      "metric_value": "150.75",
                      "threshold": "100.00",
                      "window_seconds": "3600",
                      "project_ids": "0198ec68-6e06-7253-a20b-d35c9252b9ba,0198ec68-6e06-7253-a20b-d35c9252b9bb",
                      "project_names": "Demo Project,Default Project"
                    }
                    """,
            AlertEventType.TRACE_LATENCY,
            """
                    {
                      "event_type": "TRACE_LATENCY",
                      "metric_name": "trace:latency",
                      "metric_value": "5250.5000",
                      "threshold": "5",
                      "window_seconds": "1800",
                      "project_ids": "0198ec68-6e06-7253-a20b-d35c9252b9ba,0198ec68-6e06-7253-a20b-d35c9252b9bb",
                      "project_names": "Demo Project,Default Project"
                    }
                    """));

    private static final Alert DUMMY_ALERT = Alert.builder()
            .id(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"))
            .name("Example Alert")
            .enabled(true)
            .webhook(Webhook.builder()
                    .build())
            .metadata(Map.of(ROUTING_KEY_METADATA_KEY, "example-routing-key"))
            .build();

    private static final Map<AlertType, WebhookExamples> WEBHOOK_EXAMPLES = prepareWebhookPayloadExamples();

    @Override
    public UUID create(@NonNull Alert alert) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        var newAlert = prepareAlert(alert, userName, workspaceId);

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
        var newAlert = prepareAlert(alert, userName, workspaceId);

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
    @Cacheable(name = "alert_find_all_per_workspace", key = "$workspaceId +'-'+ $eventTypes", returnType = Alert.class, wrapperType = List.class)
    public List<Alert> findAllByWorkspaceAndEventTypes(String workspaceId, @NonNull Set<AlertEventType> eventTypes) {
        log.info("Fetching all enabled alerts for workspace '{}', eventTypes '{}'", workspaceId, eventTypes);
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            AlertDAO alertDAO = handle.attach(AlertDAO.class);

            Set<String> eventTypeValues = eventTypes.stream()
                    .map(AlertEventType::getValue)
                    .collect(Collectors.toSet());

            return alertDAO.findByWorkspaceAndEventTypes(workspaceId, eventTypeValues);
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

        var event = prepareWebhookPayload(mapAlertToWebhookEvent(alert, workspaceId));

        return Mono.defer(() -> webhookHttpClient.sendWebhook(event))
                .contextWrite(ctx -> setRequestContext(ctx, userName, workspaceId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(responseBody -> {
                    log.info("Successfully sent webhook: id='{}', type='{}', url='{}', response='{}'",
                            event.getId(), event.getEventType(), event.getUrl(), responseBody);

                    return WebhookTestResult.builder()
                            .status(WebhookTestResult.Status.SUCCESS)
                            .statusCode(200) // Success defaults to 200
                            .requestBody(event.getJsonPayload())
                            .errorMessage(null)
                            .build();
                })
                .onErrorResume(throwable -> {
                    log.error("Failed to send webhook: id='{}', type='{}', url='{}', error='{}'",
                            event.getId(), event.getEventType(), event.getUrl(), throwable.getMessage(), throwable);

                    // Extract status code from RetryableHttpException if available
                    int statusCode = (throwable instanceof RetryUtils.RetryableHttpException rhe)
                            ? rhe.getStatusCode()
                            : 0;

                    return Mono.just(WebhookTestResult.builder()
                            .status(WebhookTestResult.Status.FAILURE)
                            .statusCode(statusCode)
                            .requestBody(event.getJsonPayload())
                            .errorMessage(throwable.getMessage())
                            .build());
                })
                .block();
    }

    @Override
    public WebhookExamples getWebhookExamples(@NonNull AlertType alertType) {
        return WEBHOOK_EXAMPLES.get(alertType);
    }

    private static WebhookEvent<Map<String, Object>> mapAlertToWebhookEvent(Alert alert, String workspaceId) {
        String eventId = Generators.timeBasedEpochGenerator().generate().toString();
        var eventType = CollectionUtils.isEmpty(alert.triggers())
                ? AlertEventType.TRACE_ERRORS
                : alert.triggers().getFirst().eventType();
        List<String> eventIds = List.of("0198ec7e-e844-7537-aaaa-fc5db24fb547");
        var alertId = alert.id() == null ? UUID.fromString("0198ec7e-e844-7537-aaaa-fc5dd35fb547") : alert.id();

        Map<String, Object> payload = Map.of(
                "alertId", alertId,
                "alertName", alert.name(),
                "eventType", eventType.getValue(),
                "eventIds", eventIds,
                "userNames", List.of("test-user"),
                "metadata", List.of(TEST_PAYLOAD.get(eventType)),
                "eventCount", eventIds.size(),
                "aggregationType", "consolidated",
                "message", String.format("Alert '%s': %d %s events aggregated",
                        alert.name(), eventIds.size(), eventType.getValue()));

        return WebhookEvent.<Map<String, Object>>builder()
                .id(eventId)
                .url(alert.webhook().url())
                .eventType(eventType)
                .alertType(Optional.ofNullable(alert.alertType()).orElse(AlertType.GENERAL))
                .alertId(alertId)
                .alertName(StringUtils.isBlank(alert.name()) ? "Test Alert" : alert.name())
                .alertMetadata(Optional.ofNullable(alert.metadata()).orElse(Map.of()))
                .payload(payload)
                .headers(Optional.ofNullable(alert.webhook().headers()).orElse(Map.of()))
                .secret(alert.webhook().secretToken())
                .maxRetries(1)
                .workspaceId(workspaceId)
                .workspaceName("demo_workspace_name")
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

    private Alert prepareAlert(Alert alert, String userName, String workspaceId) {
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
                .alertType(alert.alertType() != null ? alert.alertType() : AlertType.GENERAL) // Set default to GENERAL when not provided
                .webhook(webhook)
                .triggers(preparedTriggers)
                .createdBy(Optional.ofNullable(alert.createdBy()).orElse(userName))
                .lastUpdatedBy(userName)
                .workspaceId(workspaceId)
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

    private static Map<AlertType, WebhookExamples> prepareWebhookPayloadExamples() {
        Map<AlertType, WebhookExamples> result = new HashMap<>();

        Arrays.stream(AlertType.values())
                .forEach(alertType -> {
                    Map<AlertEventType, Object> examples = new HashMap<>();

                    Arrays.stream(AlertEventType.values())
                            .forEach(eventType -> {
                                var alert = DUMMY_ALERT.toBuilder()
                                        .alertType(alertType)
                                        .triggers(List.of(
                                                AlertTrigger.builder()
                                                        .eventType(eventType)
                                                        .build()))
                                        .build();

                                var webhookEvent = deserializeEventPayload(
                                        mapAlertToWebhookEvent(alert, "demo-workspace-id"));
                                examples.put(eventType, webhookEventPayloadPerType(webhookEvent));
                            });

                    result.put(alertType, WebhookExamples.builder()
                            .responseExamples(examples)
                            .build());
                });

        return result;
    }
}
