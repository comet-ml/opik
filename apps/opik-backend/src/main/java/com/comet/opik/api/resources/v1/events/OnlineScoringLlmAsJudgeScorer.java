package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.domain.ChatCompletionService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.UserLog;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.OnlineScoringConfig.StreamConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.Codec;
import org.slf4j.Logger;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.AutomationRuleEvaluatorType.LLM_AS_JUDGE;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

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
    private final Logger userFacingLogger;

    @Inject
    public OnlineScoringLlmAsJudgeScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull FeedbackScoreService feedbackScoreService) {
        this.config = config;
        this.aiProxyService = aiProxyService;
        this.feedbackScoreService = feedbackScoreService;

        this.redisReadConfig = StreamReadGroupArgs.neverDelivered().count(config.getConsumerBatchSize());
        this.consumerId = "consumer-" + config.getConsumerGroupName() + "-" + UUID.randomUUID();
        userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringLlmAsJudgeScorer.class);

        // as we are a LLM consumer, lets check only LLM stream
        initStream(config, redisson);
    }

    private void initStream(OnlineScoringConfig config, RedissonReactiveClient redisson) {
        config.getStreams().stream()
                .filter(this::isLlmAsJudge)
                .findFirst()
                .ifPresentOrElse(
                        llmConfig -> setupListener(redisson, llmConfig),
                        this::logIfEmpty);
    }

    private void logIfEmpty() {
        log.warn("No '{}' redis stream config found. Online Scoring consumer won't start.", LLM_AS_JUDGE.name());
    }

    private void setupListener(RedissonReactiveClient redisson, StreamConfiguration llmConfig) {
        var scoringCodecs = OnlineScoringCodecs.fromString(llmConfig.getCodec());
        String streamName = llmConfig.getStreamName();
        Codec codec = scoringCodecs.getCodec();

        RStreamReactive<String, TraceToScoreLlmAsJudge> stream = redisson.getStream(streamName, codec);

        log.info("OnlineScoring Scorer listening for events on stream {}", streamName);

        enforceConsumerGroup(stream);
        setupStreamListener(stream);
    }

    private boolean isLlmAsJudge(StreamConfiguration streamConfiguration) {
        return LLM_AS_JUDGE.name().equalsIgnoreCase(streamConfiguration.getScorer());
    }

    private void enforceConsumerGroup(RStreamReactive<String, TraceToScoreLlmAsJudge> stream) {
        // make sure the stream and the consumer group exists
        StreamCreateGroupArgs args = StreamCreateGroupArgs.name(config.getConsumerGroupName()).makeStream();

        stream.createGroup(args)
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
                .doOnNext(entry -> processReceivedMessages(stream, entry))
                .subscribe();
    }

    private void processReceivedMessages(RStreamReactive<String, TraceToScoreLlmAsJudge> stream,
            Map.Entry<StreamMessageId, Map<String, TraceToScoreLlmAsJudge>> entry) {
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
    }

    /**
     * Use AI Proxy to score the trace and store it as a FeedbackScore.
     * If the evaluator has multiple score definitions, it calls the LLM once per score definition.
     *
     * @param message       a Redis message with Trace to score with an Evaluator code, workspace and username
     */
    private void score(TraceToScoreLlmAsJudge message) {
        var trace = message.trace();

        // This is crucial for logging purposes to identify the rule and trace
        try (var logScope = MDC.putCloseable(UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name());
                var workspaceScope = MDC.putCloseable("workspace_id", message.workspaceId());
                var traceScope = MDC.putCloseable("trace_id", trace.id().toString());
                var ruleScope = MDC.putCloseable("rule_id", message.ruleId().toString())) {

            userFacingLogger.info("Evaluating traceId '{}' sampled by rule '{}'", trace.id(), message.ruleName());

            ChatRequest scoreRequest;
            try {
                scoreRequest = OnlineScoringEngine.prepareLlmRequest(message.llmAsJudgeCode(), trace);
            } catch (Exception e) {
                userFacingLogger.error("Error preparing LLM request for traceId '{}'", trace.id());
                throw e;
            }

            userFacingLogger.info("Sending traceId '{}' to LLM using the following input:\n\n{}", trace.id(),
                    scoreRequest);

            ChatResponse chatResponse;
            try {
                chatResponse = aiProxyService.scoreTrace(scoreRequest, message.llmAsJudgeCode().model(),
                        message.workspaceId());
                userFacingLogger.info("Received response for traceId '{}':\n\n{}", trace.id(), chatResponse);
            } catch (Exception e) {
                userFacingLogger.error("Unexpected error while scoring traceId '{}' with rule '{}'", trace.id(),
                        message.ruleName());
                throw e;
            }

            try {
                var scores = OnlineScoringEngine.toFeedbackScores(chatResponse).stream()
                        .map(item -> item.toBuilder()
                                .id(trace.id())
                                .projectId(trace.projectId())
                                .projectName(trace.projectName())
                                .build())
                        .toList();

                log.info("Received {} scores for traceId '{}' in workspace '{}'. Storing them.", scores.size(),
                        trace.id(),
                        message.workspaceId());

                feedbackScoreService.scoreBatchOfTraces(scores)
                        .contextWrite(
                                ctx -> ctx.put(RequestContext.USER_NAME, message.userName())
                                        .put(RequestContext.WORKSPACE_ID, message.workspaceId()))
                        .block();

                Map<String, List<BigDecimal>> loggedScores = scores
                        .stream()
                        .collect(groupingBy(FeedbackScoreBatchItem::name,
                                mapping(FeedbackScoreBatchItem::value, toList())));

                userFacingLogger.info("Scores for traceId '{}' stored successfully:\n\n{}", trace.id(), loggedScores);

            } catch (Exception e) {
                userFacingLogger.error("Unexpected error while storing scores for traceId '{}'", trace.id());
                throw e;
            }
        }
    }
}