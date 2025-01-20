package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AutomationRuleEvaluatorType;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.AutomationRuleEvaluatorService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.OnlineScoringConfig.StreamConfiguration;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamAddArgs;
import reactor.core.publisher.Flux;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.AutomationRuleEvaluatorType.LLM_AS_JUDGE;

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
    private final OnlineScoringConfig config;
    private final Random random = new Random(System.currentTimeMillis());

    private final Map<AutomationRuleEvaluatorType, StreamConfiguration> streamConfigurations;

    @Inject
    public OnlineScoringSampler(@Config("onlineScoring") OnlineScoringConfig config, RedissonReactiveClient redisClient,
            EventBus eventBus, AutomationRuleEvaluatorService ruleEvaluatorService) {
        this.config = config;
        this.ruleEvaluatorService = ruleEvaluatorService;
        this.redisClient = redisClient;
        eventBus.register(this);

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

            var evaluators = ruleEvaluatorService.findAll(projectId, tracesBatch.workspaceId(), LLM_AS_JUDGE);

            evaluators.forEach(evaluator -> {
                // samples traces for this rule
                var sample = traces.stream().filter(e -> random.nextFloat() < evaluator.getSamplingRate());

                switch (evaluator.getType()) {
                    case LLM_AS_JUDGE -> {
                        var messages = sample.map(trace -> TraceToScoreLlmAsJudge.builder()
                                .trace(trace)
                                .llmAsJudgeCode(evaluator.getCode())
                                .workspaceId(tracesBatch.workspaceId())
                                .userName(tracesBatch.userName())
                                .build())
                                .toList();

                        log.info("[AutomationRule '{}'] Sampled {}/{} from trace batch (expected rate: {})",
                                evaluator.getName(), messages.size(), tracesBatch.traces().size(),
                                evaluator.getSamplingRate());

                        enqueueInRedis(messages);
                    }
                    default -> log.warn("No process defined for evaluator type '{}'", evaluator.getType());
                }
            });
        });
    }

    private void enqueueInRedis(List<TraceToScoreLlmAsJudge> messages) {
        var config = streamConfigurations.get(LLM_AS_JUDGE);
        var codec = OnlineScoringCodecs.fromString(config.getCodec()).getCodec();

        RStreamReactive<String, TraceToScoreLlmAsJudge> llmAsJudgeStream = redisClient.getStream(config.getStreamName(),
                codec);

        Flux.fromIterable(messages)
                .flatMap(
                        message -> llmAsJudgeStream.add(StreamAddArgs.entry(OnlineScoringConfig.PAYLOAD_FIELD, message))
                                .doOnNext(id -> log.debug("Message sent with ID: {} into stream '{}'", id,
                                        config.getStreamName()))
                                .doOnError(err -> log.error("Error sending message: {}", err.getMessage())))
                .subscribe(noop -> {
                }, error -> log.error("Unexpected error: ", error));
    }

}
