package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.events.TraceThreadToScoreLlmAsJudge;
import com.comet.opik.api.events.TraceThreadToScoreUserDefinedMetricPython;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.OnlineScoringStreamConfigurationAdapter;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.redis.RedisStreamUtils;
import com.google.inject.ImplementedBy;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.UNKNOWN;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_ID_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_NAME_KEY;

@ImplementedBy(OnlineScorePublisherImpl.class)
public interface OnlineScorePublisher {

    /**
     * Enqueues messages into the Redis stream for online scoring. The returned publisher must be subscribed for
     * the enqueue to happen; subscribe it within a reactive context carrying the workspace (WORKSPACE_ID /
     * WORKSPACE_NAME) so the enqueue metric is labelled with it.
     *
     * @param messages the messages to enqueue
     * @param type     the type of evaluator for which the messages are intended
     * @return a {@link Mono} that completes once all messages are enqueued
     */
    Mono<Void> enqueueMessage(List<?> messages, AutomationRuleEvaluatorType type);

    /**
     * Enqueues a thread message for scoring based on the provided rule. The returned publisher must be subscribed
     * for the enqueue to happen.
     *
     * @param threadIds   the IDs of the threads to score
     * @param ruleId      the ID of the rule to apply
     * @param projectId   the ID of the project
     * @param workspaceId the ID of the workspace
     * @param userName    the name of the user who initiated the scoring
     * @return a {@link Mono} that completes once the message is enqueued
     */
    Mono<Void> enqueueThreadMessage(List<String> threadIds, UUID ruleId, UUID projectId, String workspaceId,
            String userName);

    /**
     * Enqueues a thread message for an already-resolved rule, avoiding the blocking rule lookup that the
     * {@code ruleId} overload performs. Prefer this when the caller already holds the {@link AutomationRuleEvaluator}.
     *
     * @param threadIds   the IDs of the threads to score
     * @param rule        the already-resolved automation rule evaluator
     * @param projectId   the ID of the project
     * @param workspaceId the ID of the workspace
     * @param userName    the name of the user who initiated the scoring
     * @return a {@link Mono} that completes once the message is enqueued
     */
    Mono<Void> enqueueThreadMessage(List<String> threadIds, AutomationRuleEvaluator<?, ?> rule, UUID projectId,
            String workspaceId, String userName);
}

@Singleton
@Slf4j
class OnlineScorePublisherImpl implements OnlineScorePublisher {

    private static final String METRIC_NAMESPACE = "online_scoring";
    private static final AttributeKey<String> EVALUATOR_TYPE_KEY = AttributeKey.stringKey("evaluator_type");
    private static final AttributeKey<String> RESULT_KEY = AttributeKey.stringKey("result");

    private final RedissonReactiveClient redisClient;
    private final AutomationRuleEvaluatorService automationRuleEvaluatorService;
    private final Map<AutomationRuleEvaluatorType, OnlineScoringStreamConfigurationAdapter> streamConfigurations;
    private final ServiceTogglesConfig serviceTogglesConfig;
    private final LongCounter enqueueCounter;

    @Inject
    public OnlineScorePublisherImpl(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull RedissonReactiveClient redisClient,
            @NonNull AutomationRuleEvaluatorService automationRuleEvaluatorService) {
        this.redisClient = redisClient;
        this.automationRuleEvaluatorService = automationRuleEvaluatorService;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.enqueueCounter = GlobalOpenTelemetry.getMeter(METRIC_NAMESPACE)
                .counterBuilder("%s_enqueue_total".formatted(METRIC_NAMESPACE))
                .setDescription("Messages pushed to the online-scoring Redis stream, by evaluator type, workspace and "
                        + "result (success|error). result=error counts publish failures that were previously only logged.")
                .build();
        this.streamConfigurations = config.getStreams().stream()
                .map(streamConfiguration -> {
                    var evaluatorType = AutomationRuleEvaluatorType.fromString(streamConfiguration.getScorer());
                    var adapter = OnlineScoringStreamConfigurationAdapter.create(config, evaluatorType);
                    log.info(
                            "Online Score publisher for evaluatorType: '{}' with configuration: streamName='{}', codec='{}'",
                            evaluatorType, adapter.getStreamName(), adapter.getCodec());
                    return Map.entry(evaluatorType, adapter);
                })
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Mono<Void> enqueueMessage(List<?> messages, AutomationRuleEvaluatorType type) {
        var config = streamConfigurations.get(type);
        var codec = config.getCodec();
        var stream = redisClient.getStream(config.getStreamName(), codec);
        // Resolve the workspace from the reactive context — populated upstream from RequestContext at the
        // trace/span ingest endpoint and carried down through the sampler/manual-eval chains. workspace_id
        // falls back to "unknown"; workspace_name falls back to the id (so it's never the literal "unknown").
        return Flux.deferContextual(ctx -> {
            var workspaceId = StringUtils.defaultIfBlank(ctx.getOrDefault(RequestContext.WORKSPACE_ID, UNKNOWN),
                    UNKNOWN);
            var workspaceName = StringUtils.defaultIfBlank(
                    ctx.getOrDefault(RequestContext.WORKSPACE_NAME, workspaceId), workspaceId);
            var successAttrs = Attributes.of(EVALUATOR_TYPE_KEY, type.getType(),
                    WORKSPACE_ID_KEY, workspaceId, WORKSPACE_NAME_KEY, workspaceName, RESULT_KEY, "success");
            var errorAttrs = Attributes.of(EVALUATOR_TYPE_KEY, type.getType(),
                    WORKSPACE_ID_KEY, workspaceId, WORKSPACE_NAME_KEY, workspaceName, RESULT_KEY, "error");
            return Flux.fromIterable(messages)
                    .flatMap(message -> stream.add(RedisStreamUtils.buildAddArgs(
                            OnlineScoringConfig.PAYLOAD_FIELD, message, config))
                            .doOnNext(id -> {
                                enqueueCounter.add(1, successAttrs);
                                log.debug("Message sent with ID: '{}' into stream '{}'", id, config.getStreamName());
                            })
                            .doOnError(throwable -> {
                                enqueueCounter.add(1, errorAttrs);
                                log.error("Error sending message", throwable);
                            }));
        }).then()
                // Run the enqueue (Redis stream writes) off the caller's thread — e.g. the EventBus thread for
                // the samplers — on a bounded worker pool.
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> enqueueThreadMessage(@NonNull List<String> threadIds, @NonNull UUID ruleId,
            @NonNull UUID projectId, @NonNull String workspaceId, @NonNull String userName) {

        // Only the id is known here, so resolve the rule first. findById is a blocking JDBC lookup, so do it
        // lazily on a worker thread, then delegate to the rule-based overload (which holds all enqueue logic).
        return Mono.<AutomationRuleEvaluator<?, ?>>fromCallable(
                () -> automationRuleEvaluatorService.findById(ruleId, Set.of(projectId), workspaceId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(rule -> enqueueThreadMessage(threadIds, rule, projectId, workspaceId, userName))
                .onErrorResume(NotFoundException.class, ex -> {
                    log.warn("Rule with ID '{}' not found for projectId '{}' and workspaceId '{}'", ruleId, projectId,
                            workspaceId, ex);
                    return Mono.empty();
                });
    }

    public Mono<Void> enqueueThreadMessage(@NonNull List<String> threadIds,
            @NonNull AutomationRuleEvaluator<?, ?> rule, @NonNull UUID projectId, @NonNull String workspaceId,
            @NonNull String userName) {

        // Caller already holds the resolved rule — no findById needed.
        return switch (rule) {
            case AutomationRuleEvaluatorTraceThreadLlmAsJudge llmAsJudge -> enqueueMessage(
                    List.of(toLlmAsJudgeMessage(threadIds, rule.getId(), projectId, workspaceId, userName,
                            llmAsJudge.getCode())),
                    rule.getType());
            case AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython definedMetricPython -> {
                if (serviceTogglesConfig.isTraceThreadPythonEvaluatorEnabled()) {
                    yield enqueueMessage(List.of(toDefinedMetricPython(threadIds, rule.getId(), projectId,
                            workspaceId, userName, definedMetricPython.getCode())), rule.getType());
                }
                log.warn("Trace Thread online scoring python evaluator is disabled, skipping enqueueing "
                        + "for ruleId: '{}'", rule.getId());
                yield Mono.<Void>empty();
            }
            default -> Mono.<Void>error(new IllegalStateException("Unknown rule evaluator type: " + rule));
        };
    }

    private TraceThreadToScoreLlmAsJudge toLlmAsJudgeMessage(List<String> threadIds, UUID ruleId, UUID projectId,
            String workspaceId, String userName, TraceThreadLlmAsJudgeCode code) {
        return TraceThreadToScoreLlmAsJudge.builder()
                .threadIds(threadIds)
                .ruleId(ruleId)
                .projectId(projectId)
                .workspaceId(workspaceId)
                .userName(userName)
                .code(code)
                .build();
    }

    private TraceThreadToScoreUserDefinedMetricPython toDefinedMetricPython(List<String> threadIds, UUID ruleId,
            UUID projectId, String workspaceId, String userName, TraceThreadUserDefinedMetricPythonCode code) {
        return TraceThreadToScoreUserDefinedMetricPython.builder()
                .threadIds(threadIds)
                .ruleId(ruleId)
                .projectId(projectId)
                .workspaceId(workspaceId)
                .userName(userName)
                .code(code)
                .build();
    }
}
