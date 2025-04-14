package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AutomationRuleEvaluatorType;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.dropwizard.lifecycle.Managed;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * This is the base online scorer, for all particular implementations to extend. It listens to a Redis stream for
 * Traces to be scored. Extending classes must provide a particular implementation for the score method.
 * This class implements the Managed interface to be able to start and stop the stream connected to the
 * application lifecycle.
 */
public abstract class OnlineScoringBaseScorer<M> implements Managed {

    /**
     * Logger for the actual subclass, in order to have the correct class name in the logs.
     */
    private final Logger log = LoggerFactory.getLogger(this.getClass().getName());

    private final OnlineScoringConfig config;
    private final RedissonReactiveClient redisson;
    private final FeedbackScoreService feedbackScoreService;
    private final AutomationRuleEvaluatorType type;
    private final StreamReadGroupArgs redisReadConfig;
    private final String consumerId;
    private final int batchSize;

    private volatile RStreamReactive<String, M> stream;
    private volatile Disposable streamSubscription; // Store the subscription reference

    protected static final Meter METER = GlobalOpenTelemetry.getMeter("online-scoring");
    protected LongHistogram messageProcessingTime;
    protected LongHistogram messageQueueDelay;

    protected OnlineScoringBaseScorer(@NonNull OnlineScoringConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull AutomationRuleEvaluatorType type) {
        this.config = config;
        this.redisson = redisson;
        this.feedbackScoreService = feedbackScoreService;
        this.type = type;
        this.batchSize = config.getConsumerBatchSize();
        this.redisReadConfig = StreamReadGroupArgs.neverDelivered().count(batchSize);
        this.consumerId = "consumer-" + config.getConsumerGroupName() + "-" + UUID.randomUUID();
        initializeCounters(getMetricsBaseName());
    }

    @Override
    public void start() {
        if (stream != null) {
            log.warn("Online Scoring consumer already started. Ignoring start request");
            return;
        }
        // This particular scorer implementation only consumes the respective Redis stream
        stream = initStream(config, redisson);
        log.info("Online Scoring consumer started successfully");
    }

    @Override
    public void stop() {
        log.info("Shutting down online scorer and closing stream");
        if (stream != null) {
            if (streamSubscription != null && !streamSubscription.isDisposed()) {
                log.info("Waiting for last messages to be processed before shutdown...");
                try {
                    // Read any remaining messages before stopping
                    stream.readGroup(config.getConsumerGroupName(), consumerId, redisReadConfig)
                            .flatMap(messages -> {
                                if (!messages.isEmpty()) {
                                    log.info("Processing last '{}' messages before shutdown", messages.size());
                                    return Flux.fromIterable(messages.entrySet())
                                            .publishOn(Schedulers.boundedElastic())
                                            .doOnNext(entry -> processReceivedMessages(stream, entry))
                                            .collectList()
                                            .then(Mono.fromRunnable(() -> streamSubscription.dispose()));
                                }
                                return Mono.fromRunnable(() -> streamSubscription.dispose());
                            })
                            .block(Duration.ofSeconds(2));
                } catch (Exception exception) {
                    log.error("Error processing last messages before shutdown", exception);
                }
            } else {
                log.info("No active subscription, deleting Redis stream");
            }
            stream.delete().doOnTerminate(() -> log.info("Redis Stream deleted")).subscribe();
        }
    }

    private RStreamReactive<String, M> initStream(OnlineScoringConfig config, RedissonReactiveClient redisson) {
        var configuration = config.getStreams().stream()
                .filter(streamConfiguration -> type.name().equalsIgnoreCase(streamConfiguration.getScorer()))
                .findFirst();
        if (configuration.isEmpty()) {
            log.warn("No '{}' redis stream config found. Online Scoring consumer won't start", type.name());
            return null;
        }
        return setupListener(redisson, configuration.get());
    }

    private RStreamReactive<String, M> setupListener(
            RedissonReactiveClient redisson, OnlineScoringConfig.StreamConfiguration llmConfig) {
        var scoringCodecs = OnlineScoringCodecs.fromString(llmConfig.getCodec());
        var streamName = llmConfig.getStreamName();
        var codec = scoringCodecs.getCodec();
        RStreamReactive<String, M> stream = redisson.getStream(streamName, codec);
        log.info("Online Scoring consumer listening for events on stream '{}'", streamName);
        enforceConsumerGroup(stream);
        setupStreamListener(stream);
        return stream;
    }

    private void enforceConsumerGroup(RStreamReactive<String, M> stream) {
        // Make sure the stream and the consumer group exists
        var args = StreamCreateGroupArgs.name(config.getConsumerGroupName()).makeStream();
        stream.createGroup(args)
                .onErrorResume(err -> {
                    if (err.getMessage().contains("BUSYGROUP")) {
                        log.info("Consumer group already exists, name '{}'", config.getConsumerGroupName());
                        return Mono.empty();
                    }
                    return Mono.error(err);
                })
                .subscribe();
    }

    private void setupStreamListener(RStreamReactive<String, M> stream) {
        this.streamSubscription = Flux.interval(config.getPoolingInterval().toJavaDuration())
                .onBackpressureDrop()
                .flatMap(i -> stream.readGroup(config.getConsumerGroupName(), consumerId, redisReadConfig))
                .onErrorContinue((throwable, object) -> log.error("Error reading from Redis stream", throwable))
                .flatMapIterable(Map::entrySet)
                .flatMap(entry -> Mono.fromRunnable(() -> processReceivedMessages(stream, entry))
                        .subscribeOn(Schedulers.boundedElastic()),
                        batchSize) // Concurrency hint
                .onErrorContinue(
                        (throwable, object) -> log.error("Error processing message from Redis stream", throwable))
                .subscribe();
    }

    private void processReceivedMessages(
            RStreamReactive<String, M> stream, Map.Entry<StreamMessageId, Map<String, M>> entry) {
        var messageId = entry.getKey();
        try {
            // Record queue delay
            var messageTimestamp = extractTimeFromMessageId(messageId);
            messageTimestamp.ifPresent(msgTime -> {
                var queueDelay = System.currentTimeMillis() - msgTime;
                messageQueueDelay.record(queueDelay);
            });

            var message = entry.getValue().get(OnlineScoringConfig.PAYLOAD_FIELD);
            log.info("Message received with id '{}'", messageId);

            long startProcessingTime = System.currentTimeMillis();
            score(message);
            long processingTime = System.currentTimeMillis() - startProcessingTime;
            messageProcessingTime.record(processingTime);

            // Remove messages from Redis pending list
            stream.ack(config.getConsumerGroupName(), messageId).subscribe();
            stream.remove(messageId).subscribe();
        } catch (Exception exception) {
            log.error("Error processing message id '{}'", messageId, exception);
        }
    }

    /**
     * Provide a particular implementation to score the trace and store it as a FeedbackScore.
     * @param message a Redis message with Trace to score, workspace and username.
     */
    protected abstract void score(M message);

    protected Map<String, List<BigDecimal>> storeScores(
            List<FeedbackScoreBatchItem> scores, Trace trace, String userName, String workspaceId) {
        log.info("Received '{}' scores for traceId '{}' in workspace '{}'. Storing them",
                scores.size(), trace.id(), workspaceId);
        feedbackScoreService.scoreBatchOfTraces(scores)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();
        return scores.stream()
                .collect(Collectors.groupingBy(FeedbackScoreBatchItem::name,
                        Collectors.mapping(FeedbackScoreBatchItem::value, Collectors.toList())));
    }

    private void initializeCounters(String baseName) {
        messageProcessingTime = METER
                .histogramBuilder("online_scoring_" + baseName + "_processing_time")
                .setDescription("Time taken to process a message")
                .setUnit("ms")
                .ofLongs()
                .build();

        messageQueueDelay = METER
                .histogramBuilder("online_scoring_" + baseName + "_queue_delay")
                .setDescription("Delay between message insertion in Redis and processing start")
                .setUnit("ms")
                .ofLongs()
                .build();
    }

    abstract String getMetricsBaseName();

    /**
     * Redis Streams messageId are in the format <millisecondsTime>-<sequenceNumber>
     * @param messageId a Redis Stream messageId
     * @return a timestamp extracted from the messageId
     */
    private Optional<Long> extractTimeFromMessageId(StreamMessageId messageId) {
        String idString = messageId.toString();
        String[] parts = idString.split("-");
        if (parts.length > 0) {
            try {
                return Optional.of(Long.parseLong(parts[0]));
            } catch (NumberFormatException e) {
                log.warn("Failed to parse timestamp from message ID: {}", idString, e);
            }
        }
        return Optional.empty();
    }
}
