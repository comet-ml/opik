package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.events.TraceThreadToScoreLlmAsJudge;
import com.comet.opik.api.events.TraceThreadToScoreUserDefinedMetricPython;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.redis.RedisStreamCodec;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamAddArgs;
import reactor.core.publisher.Flux;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode;

@ImplementedBy(OnlineScorePublisherImpl.class)
public interface OnlineScorePublisher {

    /**
     * Enqueues messages into the Redis stream for online scoring.
     *
     * @param messages the messages to enqueue
     * @param type     the type of evaluator for which the messages are intended
     */
    void enqueueMessage(List<?> messages, AutomationRuleEvaluatorType type);

    /**
     * Enqueues a thread message for scoring based on the provided rule.
     *
     * @param threadIds   the IDs of the threads to score
     * @param ruleId      the ID of the rule to apply
     * @param projectId   the ID of the project
     * @param workspaceId the ID of the workspace
     * @param userName    the name of the user who initiated the scoring
     */
    void enqueueThreadMessage(List<String> threadIds, UUID ruleId, UUID projectId, String workspaceId, String userName);
}

@Singleton
@Slf4j
class OnlineScorePublisherImpl implements OnlineScorePublisher {

    private final RedissonReactiveClient redisClient;
    private final AutomationRuleEvaluatorService automationRuleEvaluatorService;
    private final Map<AutomationRuleEvaluatorType, OnlineScoringConfig.StreamConfiguration> streamConfigurations;
    private final ServiceTogglesConfig serviceTogglesConfig;

    @Inject
    public OnlineScorePublisherImpl(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull RedissonReactiveClient redisClient,
            @NonNull AutomationRuleEvaluatorService automationRuleEvaluatorService) {
        this.redisClient = redisClient;
        this.automationRuleEvaluatorService = automationRuleEvaluatorService;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.streamConfigurations = config.getStreams().stream()
                .map(streamConfiguration -> {
                    var evaluatorType = AutomationRuleEvaluatorType.fromString(streamConfiguration.getScorer());
                    if (evaluatorType != null) {
                        log.info(
                                "Online Score publisher for evaluatorType: '{}' with configuration: streamName='{}', codec='{}'",
                                evaluatorType, streamConfiguration.getStreamName(), streamConfiguration.getCodec());
                        return Map.entry(evaluatorType, streamConfiguration);
                    } else {
                        log.warn("No Online Score publisher for evaluatorType: '{}'", streamConfiguration.getScorer());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public void enqueueMessage(List<?> messages, AutomationRuleEvaluatorType type) {
        var config = streamConfigurations.get(type);
        var codec = RedisStreamCodec.fromString(config.getCodec()).getCodec();
        var llmAsJudgeStream = redisClient.getStream(config.getStreamName(), codec);
        Flux.fromIterable(messages)
                .flatMap(message -> llmAsJudgeStream
                        .add(StreamAddArgs.entry(OnlineScoringConfig.PAYLOAD_FIELD, message))
                        .doOnNext(id -> log.debug("Message sent with ID: '{}' into stream '{}'",
                                id, config.getStreamName()))
                        .doOnError(throwable -> log.error("Error sending message", throwable)))
                .subscribe(id -> {
                    // noop
                }, throwable -> log.error("Unexpected error when enqueueing messages into redis", throwable));
    }

    public void enqueueThreadMessage(@NonNull List<String> threadIds, @NonNull UUID ruleId, @NonNull UUID projectId,
            @NonNull String workspaceId, @NonNull String userName) {

        AutomationRuleEvaluator<?, ?> rule;

        try {
            rule = automationRuleEvaluatorService.findById(ruleId, Set.of(projectId), workspaceId);
        } catch (NotFoundException ex) {
            log.warn("Rule with ID '{}' not found for projectId '{}' and workspaceId '{}'", ruleId, projectId,
                    workspaceId, ex);
            return;
        }

        switch (rule) {
            case AutomationRuleEvaluatorTraceThreadLlmAsJudge llmAsJudge -> {
                var message = toLlmAsJudgeMessage(threadIds, ruleId, projectId, workspaceId, userName,
                        llmAsJudge.getCode());
                enqueueMessage(List.of(message), rule.getType());
            }
            case AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython definedMetricPython -> {
                var message = toDefinedMetricPython(threadIds, ruleId, projectId, workspaceId, userName,
                        definedMetricPython.getCode());

                if (serviceTogglesConfig.isTraceThreadPythonEvaluatorEnabled()) {
                    enqueueMessage(List.of(message), rule.getType());
                } else {
                    log.warn(
                            "Trace Thread online scoring python evaluator is disabled, skipping enqueueing for ruleId: '{}'",
                            ruleId);
                }
            }
            default -> throw new IllegalStateException("Unknown rule evaluator type: " + rule);
        }

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
