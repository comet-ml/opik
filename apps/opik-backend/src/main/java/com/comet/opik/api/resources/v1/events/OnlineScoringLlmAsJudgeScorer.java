package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.domain.ChatCompletionService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.UUID;

import static com.comet.opik.api.AutomationRuleEvaluatorType.LLM_AS_JUDGE;

/**
 * This service listens a Redis stream for Traces to be scored in a LLM provider. It will prepare the LLM request
 * by rendering message templates using values from the Trace and prepare the schema for the return (structured output).
 */
@EagerSingleton
@Slf4j
public class OnlineScoringLlmAsJudgeScorer {

    private final OnlineScoringConfig config;
    private final ChatCompletionService aiProxyService;
    private final FeedbackScoreService feedbackScoreService;

    private final String consumerId;
    private final StreamReadGroupArgs redisReadConfig;

    @Inject
    public OnlineScoringLlmAsJudgeScorer(@Config("onlineScoring") OnlineScoringConfig config,
            RedissonReactiveClient redisson,
            ChatCompletionService aiProxyService,
            FeedbackScoreService feedbackScoreService) {
        this.config = config;
        this.aiProxyService = aiProxyService;
        this.feedbackScoreService = feedbackScoreService;

        var codec = LLM_AS_JUDGE.getMessageCodec();
        RStreamReactive<String, TraceToScoreLlmAsJudge> stream = redisson.getStream(config.getLlmAsJudgeStream(),
                codec);
        log.info("OnlineScoring Scorer listening for events on stream {}", config.getLlmAsJudgeStream());

        redisReadConfig = StreamReadGroupArgs.neverDelivered().count(config.getConsumerBatchSize());
        consumerId = "consumer-" + config.getConsumerGroupName() + "-" + UUID.randomUUID();

        enforceConsumerGroup(stream);
        setupStreamListener(stream);
    }

    /**
     * Use AI Proxy to score the trace and store it as a FeedbackScore.
     * If the evaluator has multiple score definitions, it calls the LLM once per score definition.
     *
     * @param message       a Redis message with Trace to score with an Evaluator code, workspace and username
     */
    private void score(TraceToScoreLlmAsJudge message) {
        var trace = message.trace();
        var scoreRequest = OnlineScoringEngine.prepareLlmRequest(message.llmAsJudgeCode(), trace);

        var chatResponse = aiProxyService.scoreTrace(scoreRequest, message.llmAsJudgeCode().model(),
                message.workspaceId());

        var scores = OnlineScoringEngine.toFeedbackScores(chatResponse).stream()
                .map(item -> item.toBuilder()
                        .id(trace.id())
                        .projectId(trace.projectId())
                        .projectName(trace.projectName())
                        .build())
                .toList();

        log.info("Received {} scores for traceId '{}' in workspace '{}'. Storing them.", scores.size(), trace.id(),
                message.workspaceId());

        feedbackScoreService.scoreBatchOfTraces(scores)
                .contextWrite(
                        ctx -> ctx.put(RequestContext.USER_NAME, message.userName())
                                .put(RequestContext.WORKSPACE_ID, message.workspaceId()))
                .block();
    }

    private void enforceConsumerGroup(RStreamReactive<String, TraceToScoreLlmAsJudge> stream) {
        // make sure the stream and the consumer group exists
        var createGroupArgs = StreamCreateGroupArgs.name(config.getConsumerGroupName()).makeStream();

        stream.createGroup(createGroupArgs)
                .onErrorResume(err -> {
                    if (err.getMessage().contains("BUSYGROUP")) {
                        log.info("Consumer group already exists: {}", config.getConsumerGroupName());
                        return Mono.empty();
                    }
                    return Mono.error(err);
                })
                .subscribe();
    }

    private void setupStreamListener(RStreamReactive<String, TraceToScoreLlmAsJudge> stream) {
        // Listen for messages
        Flux.interval(config.getPoolingInterval().toJavaDuration())
                .flatMap(i -> stream.readGroup(config.getConsumerGroupName(), consumerId, redisReadConfig))
                .flatMap(messages -> Flux.fromIterable(messages.entrySet()))
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(entry -> {
                    var messageId = entry.getKey();

                    try {
                        var message = entry.getValue().get(OnlineScoringConfig.PAYLOAD_FIELD);

                        log.info("Message received [{}]: traceId '{}' from user '{}' to be scored in '{}'", messageId,
                                message.trace().id(), message.userName(), message.llmAsJudgeCode().model().name());

                        score(message);

                        // remove messages from Redis pending list
                        stream.ack(config.getConsumerGroupName(), messageId).subscribe();
                        stream.remove(messageId).subscribe();
                    } catch (Exception e) {
                        log.error("Error processing message [{}]: {}", messageId, e.getMessage(), e);
                    }
                })
                .subscribe();
    }
}
