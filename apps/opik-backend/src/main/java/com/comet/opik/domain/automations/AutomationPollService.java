package com.comet.opik.domain.automations;

import com.comet.opik.api.automations.Automation;
import com.comet.opik.api.automations.AutomationRun;
import com.comet.opik.domain.OptimizationDAO;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.evaluators.AutomationRuleAutomationDAO;
import com.comet.opik.domain.evaluators.AutomationRuleAutomationModel;
import com.comet.opik.infrastructure.AutomationEventsConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * What the poll job does on each tick: fires the counted triggers whose count since their last firing
 * reached N, resumes runs parked on an optimization that reached a terminal status, and fails runs
 * parked longer than the wait timeout.
 */
@Singleton
@Slf4j
public class AutomationPollService {

    static final int DEFAULT_SUITE_GROWTH = 50;
    static final int DEFAULT_ANNOTATIONS = 20;
    private static final TypeReference<List<Automation.Trigger>> TRIGGERS = new TypeReference<>() {
    };

    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull AutomationActivityDAO activityDAO;
    private final @NonNull AutomationEventPublisher publisher;
    private final @NonNull AutomationRunService runService;
    private final @NonNull OptimizationDAO optimizationDAO;
    private final @NonNull WorkspaceNameService workspaceNameService;
    private final @NonNull AutomationEventsConfig config;
    private final @NonNull OpikConfiguration opikConfiguration;

    @Inject
    public AutomationPollService(@NonNull TransactionTemplate transactionTemplate,
            @NonNull AutomationActivityDAO activityDAO, @NonNull AutomationEventPublisher publisher,
            @NonNull AutomationRunService runService, @NonNull OptimizationDAO optimizationDAO,
            @NonNull WorkspaceNameService workspaceNameService,
            @NonNull @Config("automationEvents") AutomationEventsConfig config,
            @NonNull OpikConfiguration opikConfiguration) {
        this.transactionTemplate = transactionTemplate;
        this.activityDAO = activityDAO;
        this.publisher = publisher;
        this.runService = runService;
        this.optimizationDAO = optimizationDAO;
        this.workspaceNameService = workspaceNameService;
        this.config = config;
        this.opikConfiguration = opikConfiguration;
    }

    /** A poll-born event has no request to take the name from, and the optimizer's SDK job needs it. */
    private String workspaceName(String workspaceId) {
        try {
            return workspaceNameService.getWorkspaceName(workspaceId,
                    opikConfiguration.getAuthentication().getReactService().url());
        } catch (RuntimeException error) {
            log.warn("Could not resolve the name of workspace '{}', using its id", workspaceId, error);
            return workspaceId;
        }
    }

    public Mono<Void> tick() {
        return fireCountedTriggers().then(resumeFinishedOptimizations()).then(failTimedOut());
    }

    private Mono<Void> fireCountedTriggers() {
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY, handle -> {
            try (var rows = handle.attach(AutomationRuleAutomationDAO.class).findEnabledListening(
                    Automation.Event.TEST_SUITE_GREW.getValue(),
                    Automation.Event.ANNOTATION_QUEUE_SUBMITTED.getValue())) {
                return rows.toList();
            }
        }))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .filter(model -> model.pausedUntil() == null || model.pausedUntil().isBefore(Instant.now()))
                .concatMap(model -> Flux.fromIterable(JsonUtils.readValue(model.triggers(), TRIGGERS))
                        .filter(trigger -> Optional.ofNullable(Automation.Event.fromString(trigger.event()))
                                .map(Automation.Event::isCounted).orElse(false))
                        .concatMap(trigger -> check(model, trigger)
                                .onErrorResume(error -> {
                                    log.warn("Counted trigger check failed, automation '{}', trigger '{}'", model.id(),
                                            trigger.event(), error);
                                    return Mono.empty();
                                })))
                .then();
    }

    private Mono<Void> check(AutomationRuleAutomationModel model, Automation.Trigger trigger) {
        Automation.Event event = Objects.requireNonNull(Automation.Event.fromString(trigger.event()));
        UUID targetId = event == Automation.Event.TEST_SUITE_GREW ? trigger.testSuiteId() : trigger.annotationQueueId();
        if (targetId == null) {
            return Mono.empty();
        }
        String key = "%s:%s".formatted(event.getValue(), targetId);
        int needed = Optional.ofNullable(trigger.n())
                .orElse(event == Automation.Event.TEST_SUITE_GREW ? DEFAULT_SUITE_GROWTH : DEFAULT_ANNOTATIONS);

        return Mono.fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AutomationWatermarkDAO.class).find(model.id(), key)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(watermark -> watermark.map(AutomationWatermarkDAO.Watermark::lastFiredAt)
                        .orElse(model.createdAt()))
                .flatMap(since -> {
                    Mono<Long> count = event == Automation.Event.TEST_SUITE_GREW
                            ? activityDAO.countDatasetItemsSince(targetId, since)
                            : activityDAO.countAnnotatedSince(targetId, model.projectId(), since);
                    return count
                            .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, model.workspaceId())
                                    .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER))
                            .flatMap(seen -> seen < needed
                                    ? Mono.empty()
                                    : fire(model, trigger, event, targetId, key, seen, since));
                });
    }

    private Mono<Void> fire(AutomationRuleAutomationModel model, Automation.Trigger trigger, Automation.Event event,
            UUID targetId, String key, long seen, Instant since) {
        Instant now = Instant.now();
        Map<String, Object> payload = new HashMap<>();
        payload.put("count", seen);
        payload.put("since", since.toString());
        if (event == Automation.Event.TEST_SUITE_GREW) {
            payload.put("test_suite_id", targetId.toString());
            payload.put("test_suite_name", trigger.testSuiteName());
        } else {
            payload.put("annotation_queue_id", targetId.toString());
            payload.put("annotation_queue_name", trigger.annotationQueueName());
        }
        return Mono.fromRunnable(() -> transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(AutomationWatermarkDAO.class).save(model.id(), key, now,
                    (int) Math.min(seen, Integer.MAX_VALUE));
            return null;
        }))
                .subscribeOn(Schedulers.boundedElastic())
                .then(publisher.enqueue(AutomationEvent.builder()
                        .workspaceId(model.workspaceId())
                        .workspaceName(workspaceName(model.workspaceId()))
                        .userName(RequestContext.SYSTEM_USER)
                        .projectId(model.projectId())
                        .event(event.getValue())
                        .itemId("%s@%s".formatted(targetId, now))
                        .payload(payload)
                        .depth(0)
                        .build()))
                .doOnSuccess(__ -> log.info("Counted trigger fired, automation '{}', event '{}', seen {} since {}",
                        model.id(), event.getValue(), seen, since));
    }

    /**
     * Optimizations post no event when they finish, so parked runs are checked here. The check reads the
     * bare row: the full read aggregates every trial's scores and costs, which is the executor's job once,
     * not the poll's every tick.
     */
    private Mono<Void> resumeFinishedOptimizations() {
        String prefix = AutomationExecutor.waitKey(Automation.Event.OPTIMIZATION_FINISHED, "");
        return Mono.fromCallable(() -> runService.findWaitingByPrefix(prefix))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .concatMap(run -> {
                    UUID optimizationId = UUID.fromString(run.waitingOn().substring(prefix.length()));
                    return optimizationDAO.getRowById(optimizationId)
                            .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, run.workspaceId())
                                    .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER))
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty())
                            .flatMap(found -> {
                                if (found.isEmpty()) {
                                    // The optimization is gone (deleted, or never persisted): the run can't resume.
                                    return Mono.fromRunnable(() -> runService.finishSingle(run.workspaceId(), run.id(),
                                            AutomationRun.Status.FAILED,
                                            run.outputs() == null
                                                    ? Map.of()
                                                    : JsonUtils.treeToValue(run.outputs(), Map.class),
                                            List.of(), null, null,
                                            "optimization '%s' no longer exists".formatted(optimizationId)))
                                            .subscribeOn(Schedulers.boundedElastic()).then();
                                }
                                var optimization = found.get();
                                if (optimization.status() == null || !optimization.status().isTerminal()) {
                                    return Mono.empty();
                                }
                                Map<String, Object> payload = new HashMap<>();
                                payload.put("optimization_id", optimizationId.toString());
                                payload.put("status", optimization.status().getValue());
                                payload.put("dataset_id", String.valueOf(optimization.datasetId()));
                                payload.put("objective_name", optimization.objectiveName());
                                if (optimization.errorInfo() != null && optimization.errorInfo().message() != null) {
                                    payload.put("error", optimization.errorInfo().message());
                                }
                                return publisher.enqueue(AutomationEvent.builder()
                                        .workspaceId(run.workspaceId())
                                        .workspaceName(workspaceName(run.workspaceId()))
                                        .userName(RequestContext.SYSTEM_USER)
                                        .projectId(run.projectId())
                                        .event(Automation.Event.OPTIMIZATION_FINISHED.getValue())
                                        .itemId(optimizationId.toString())
                                        .payload(payload)
                                        .depth(run.depth())
                                        .build());
                            })
                            .onErrorResume(error -> {
                                log.warn("Could not check optimization '{}' for run '{}'", optimizationId, run.id(),
                                        error);
                                return Mono.empty();
                            });
                })
                .then();
    }

    private Mono<Void> failTimedOut() {
        Instant cutoff = Instant.now().minus(config.getWaitTimeout().toJavaDuration());
        return Mono.fromRunnable(() -> runService.failParkedBefore(cutoff,
                "timed out after %s waiting".formatted(config.getWaitTimeout())))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
