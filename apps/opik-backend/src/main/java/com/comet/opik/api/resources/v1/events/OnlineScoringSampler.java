package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AutomationRuleEvaluator;
import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.AutomationRuleEvaluatorType;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.ScoringMessage;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.events.TraceToScoreUserDefinedMetricPython;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.AutomationRuleEvaluatorService;
import com.comet.opik.domain.UserLog;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.OnlineScoringConfig.StreamConfiguration;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.slf4j.Logger;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.AutomationRuleEvaluatorType.LLM_AS_JUDGE;
import static com.comet.opik.api.AutomationRuleEvaluatorType.USER_DEFINED_METRIC_PYTHON;

/**
 * This service listens for Traces creation server in-memory event (via EventBus). When it happens, it fetches
 * Automation Rules for the trace's project and samples the trace batch for the proper scoring. The trace and code
 * (which can be a LLM-as-Judge, a Python code or new integrations we add) are enqueued in a Redis stream dedicated
 * to that evaluator type.
 */
@EagerSingleton
@Slf4j
public class OnlineScoringSampler {

    private final AutomationRuleEvaluatorService ruleEvaluatorService;
    private final RedissonReactiveClient redisClient;
    private final Random random = new Random(Instant.now().toEpochMilli());
    private final Logger userFacingLogger;

    private final Map<AutomationRuleEvaluatorType, StreamConfiguration> streamConfigurations;

    @Inject
    public OnlineScoringSampler(@Config("onlineScoring") @NonNull OnlineScoringConfig config,
            @NonNull RedissonReactiveClient redisClient,
            @NonNull EventBus eventBus, @NonNull AutomationRuleEvaluatorService ruleEvaluatorService) {
        this.ruleEvaluatorService = ruleEvaluatorService;
        this.redisClient = redisClient;
        eventBus.register(this);
        userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringSampler.class);

        streamConfigurations = config.getStreams().stream()
                .map(streamConfiguration -> {
                    var evaluatorType = AutomationRuleEvaluatorType.fromString(streamConfiguration.getScorer());
                    if (evaluatorType != null) {
                        log.info("Redis Stream map: '{}' -> '{}'", evaluatorType, streamConfiguration);
                        return Map.entry(evaluatorType, streamConfiguration);
                    } else {
                        log.warn("No such evaluator type '{}'", streamConfiguration.getScorer());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Listen for trace batches to check for existent Automation Rules to score them. It samples the trace batch and
     * enqueues the sample into Redis Stream.
     *
     * @param tracesBatch a traces batch with workspaceId and userName
     */
    @Subscribe
    public void onTracesCreated(TracesCreated tracesBatch) {
        Map<UUID, List<Trace>> tracesByProject = tracesBatch.traces().stream()
                .collect(Collectors.groupingBy(Trace::projectId));

        Map<String, Integer> countMap = tracesByProject.entrySet().stream()
                .collect(Collectors.toMap(entry -> "projectId: " + entry.getKey(),
                        entry -> entry.getValue().size()));

        log.debug("Received {} traces for workspace '{}': {}", tracesBatch.traces().size(), tracesBatch.workspaceId(),
                countMap);

        // fetch automation rules per project
        tracesByProject.forEach((projectId, traces) -> {
            log.debug("Fetching evaluators for {} traces, project '{}' on workspace '{}'",
                    traces.size(), projectId, tracesBatch.workspaceId());

            List<? extends AutomationRuleEvaluator<?>> evaluatorsLlmAsJudge = ruleEvaluatorService.findAll(projectId,
                    tracesBatch.workspaceId(), LLM_AS_JUDGE);
            List<? extends AutomationRuleEvaluator<?>> evaluatorsUserDefinedMetricPython = ruleEvaluatorService
                    .findAll(projectId, tracesBatch.workspaceId(), USER_DEFINED_METRIC_PYTHON);

            // Important to set the workspaceId for logging purposes
            try (MDC.MDCCloseable logScope = MDC.putCloseable(UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name());
                    MDC.MDCCloseable scope = MDC.putCloseable("workspace_id", tracesBatch.workspaceId())) {

                Stream.concat(evaluatorsLlmAsJudge.stream(), evaluatorsUserDefinedMetricPython.stream())
                        .forEach(evaluator -> {
                            // samples traces for this rule
                            var samples = traces.stream().filter(trace -> shouldSampleTrace(evaluator, trace));

                            switch (evaluator.getType()) {
                                case LLM_AS_JUDGE -> {
                                    var messages = samples
                                            .map(trace -> toLlmAsJudgeMessage(tracesBatch,
                                                    (AutomationRuleEvaluatorLlmAsJudge) evaluator, trace))
                                            .toList();

                                    log.info("[AutomationRule '{}' Sampled {}/{} from trace batch (expected rate: {})",
                                            evaluator.getName(), messages.size(), tracesBatch.traces().size(),
                                            evaluator.getSamplingRate());

                                    enqueueInRedis(messages, LLM_AS_JUDGE);
                                }
                                case USER_DEFINED_METRIC_PYTHON -> {
                                    var messages = samples
                                            .map(trace -> toScoreUserDefinedMetricPython(tracesBatch,
                                                    (AutomationRuleEvaluatorLlmAsJudge) evaluator, trace))
                                            .toList();

                                    log.info("[AutomationRule '{}' Sampled {}/{} from trace batch (expected rate: {})",
                                            evaluator.getName(), messages.size(), tracesBatch.traces().size(),
                                            evaluator.getSamplingRate());

                                    enqueueInRedis(messages, USER_DEFINED_METRIC_PYTHON);
                                }
                                default -> log.warn("No process defined for evaluator type '{}'", evaluator.getType());
                            }
                        });
            }
        });
    }

    private boolean shouldSampleTrace(AutomationRuleEvaluator<?> evaluator, Trace trace) {
        var shouldBeSampled = random.nextFloat() < evaluator.getSamplingRate();

        if (!shouldBeSampled) {
            try (var ruleScope = MDC.putCloseable("rule_id", evaluator.getId().toString());
                    var traceScope = MDC.putCloseable("trace_id", trace.id().toString())) {
                userFacingLogger.info(
                        "The traceId '{}' was skipped for rule: '{}' and per the sampling rate '{}'",
                        trace.id(), evaluator.getName(), evaluator.getSamplingRate());
            }
        }

        return shouldBeSampled;
    }

    private ScoringMessage toLlmAsJudgeMessage(TracesCreated tracesBatch,
            AutomationRuleEvaluatorLlmAsJudge evaluator,
            Trace trace) {
        return TraceToScoreLlmAsJudge.builder()
                .trace(trace)
                .ruleId(evaluator.getId())
                .ruleName(evaluator.getName())
                .llmAsJudgeCode(evaluator.getCode())
                .workspaceId(tracesBatch.workspaceId())
                .userName(tracesBatch.userName())
                .build();
    }

    private ScoringMessage toScoreUserDefinedMetricPython(TracesCreated tracesBatch,
            AutomationRuleEvaluatorLlmAsJudge evaluator,
            Trace trace) {
        return TraceToScoreUserDefinedMetricPython.builder()
                .trace(trace)
                .ruleId(evaluator.getId())
                .ruleName(evaluator.getName())
                .llmAsJudgeCode(evaluator.getCode())
                .workspaceId(tracesBatch.workspaceId())
                .userName(tracesBatch.userName())
                .build();
    }

    private void enqueueInRedis(List<ScoringMessage> messages, AutomationRuleEvaluatorType type) {
        var config = streamConfigurations.get(type);
        var codec = OnlineScoringCodecs.fromString(config.getCodec()).getCodec();
        var llmAsJudgeStream = redisClient.getStream(config.getStreamName(), codec);

        Flux.fromIterable(messages)
                .flatMap(message -> llmAsJudgeStream
                        .add(StreamAddArgs.entry(OnlineScoringConfig.PAYLOAD_FIELD, message))
                        .doOnNext(id -> successLog(id, config))
                        .doOnError(this::errorLog))
                .subscribe(this::noop, this::logFluxCompletionError);
    }

    private void noop(StreamMessageId id) {
        // no-op
    }

    private void logFluxCompletionError(Throwable error) {
        log.error("Unexpected error when enqueueing messages into redis: ", error);
    }

    private void errorLog(Throwable err) {
        log.error("Error sending message: {}", err.getMessage());
    }

    private void successLog(StreamMessageId id, StreamConfiguration config) {
        log.debug("Message sent with ID: {} into stream '{}'", id, config.getStreamName());
    }

}
