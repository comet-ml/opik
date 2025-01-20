package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Trace;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.AutomationRuleEvaluatorService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamAddArgs;
import reactor.core.publisher.Flux;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Map;
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

    @Inject
    public OnlineScoringSampler(@Config("onlineScoring") OnlineScoringConfig config, RedissonReactiveClient redisClient,
            EventBus eventBus, AutomationRuleEvaluatorService ruleEvaluatorService) {
        this.config = config;
        this.ruleEvaluatorService = ruleEvaluatorService;
        this.redisClient = redisClient;
        eventBus.register(this);
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
                var messages = traces.stream()
                        .filter(e -> random.nextFloat() < evaluator.getSamplingRate()) // samples traces for this rule
                        .map(trace -> TraceToScoreLlmAsJudge.builder()
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
            });
        });
    }

    private void enqueueInRedis(List<TraceToScoreLlmAsJudge> messages) {
        var llmAsJudgeStream = redisClient.getStream(config.getLlmAsJudgeStream(), LLM_AS_JUDGE.getMessageCodec());

        Flux.fromIterable(messages)
                .flatMap(
                        message -> llmAsJudgeStream.add(StreamAddArgs.entry(OnlineScoringConfig.PAYLOAD_FIELD, message))
                                .doOnNext(id -> log.debug("Message sent with ID: {} into stream '{}'", id,
                                        config.getLlmAsJudgeStream()))
                                .doOnError(err -> log.error("Error sending message: {}", err.getMessage())))
                .subscribe();
    }

}
