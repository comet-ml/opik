package com.comet.opik.api.resources.v1.events;

import com.comet.opik.infrastructure.StreamConfiguration;
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

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * This is the Base Redis Subscriber, for all particular implementations to extend. It listens to a Redis stream.
 * Extending classes must provide a particular implementation for the processEvent method.
 * This class implements the Managed interface to be able to start and stop the stream connected to the
 * application lifecycle.
 */
public abstract class BaseRedisSubscriber<M> implements Managed {

    private static final String BUSYGROUP = "BUSYGROUP";

    /**
     * Logger for the actual subclass, in order to have the correct class name in the logs.
     */
    private final Logger log = LoggerFactory.getLogger(this.getClass().getName());

    private final StreamConfiguration config;
    private final RedissonReactiveClient redisson;
    private final StreamReadGroupArgs redisReadConfig;
    private final String consumerId;
    private final int batchSize;

    private volatile RStreamReactive<String, M> stream;
    private volatile Disposable streamSubscription; // Store the subscription reference

    protected final Meter meter;
    protected final LongHistogram messageProcessingTime;
    protected final LongHistogram messageQueueDelay;
    protected final String payloadField;

    protected BaseRedisSubscriber(@NonNull StreamConfiguration config, @NonNull RedissonReactiveClient redisson,
            @NonNull String metricsBaseName, @NonNull String payloadField) {
        this.payloadField = payloadField;
        this.config = config;
        this.redisson = redisson;
        this.batchSize = config.getConsumerBatchSize();
        this.redisReadConfig = StreamReadGroupArgs.neverDelivered().count(batchSize);
        this.consumerId = "consumer-" + config.getConsumerGroupName() + "-" + UUID.randomUUID();

        String metricNamespace = getMetricNamespace();

        this.meter = GlobalOpenTelemetry.getMeter(metricNamespace);

        this.messageProcessingTime = meter
                .histogramBuilder("%s_%s_processing_time".formatted(metricNamespace, metricsBaseName))
                .setDescription("Time taken to process a message")
                .setUnit("ms")
                .ofLongs()
                .build();

        this.messageQueueDelay = meter
                .histogramBuilder("%s_%s_queue_delay".formatted(metricNamespace, metricsBaseName))
                .setDescription("Delay between message insertion in Redis and processing start")
                .setUnit("ms")
                .ofLongs()
                .build();
    }

    protected abstract String getMetricNamespace();

    protected String getSubscriberName() {
        return this.getClass().getSimpleName().replaceAll("(?<=[a-z])(?=[A-Z])", " ");
    }

    @Override
    public void start() {
        if (stream != null) {
            log.warn("{} consumer already started. Ignoring start request", getSubscriberName());
            return;
        }
        // Log configuration values
        log.info("{} consumer starting with configuration: streamName='{}', consumerGroupName='{}', consumerBatchSize='{}', poolingInterval='{}'",
                getSubscriberName(),
                config.getStreamName(),
                config.getConsumerGroupName(),
                batchSize,
                config.getPoolingInterval().toJavaDuration());
        // This particular subscriber implementation only consumes the respective Redis stream
        stream = initStream(config, redisson);
        log.info("{} consumer started successfully", getSubscriberName());
    }

    @Override
    public void stop() {
        log.info("Shutting down '{}' and closing stream", getSubscriberName());

        if (stream == null) {
            return;
        }

        if (streamSubscription == null || streamSubscription.isDisposed()) {
            log.info("No active subscription, deleting Redis stream");
            stream.delete().doOnTerminate(() -> log.info("Redis Stream deleted")).subscribe();
            return;
        }

        log.info("Waiting for last messages to be processed before shutdown...");
        try {
            // Read any remaining messages before stopping
            stream.readGroup(config.getConsumerGroupName(), consumerId, redisReadConfig)
                    .flatMap(messages -> {
                        if (!messages.isEmpty()) {
                            log.info("Processing last '{}' messages before shutdown", messages.size());
                            return Flux.fromIterable(messages.entrySet())
                                    .publishOn(Schedulers.boundedElastic())
                                    .flatMap(entry -> processReceivedMessages(stream, entry))
                                    .collectList()
                                    .then(Mono.fromRunnable(() -> streamSubscription.dispose()));
                        }
                        return Mono.fromRunnable(() -> streamSubscription.dispose());
                    })
                    .block(Duration.ofSeconds(2));
        } catch (Exception exception) {
            log.error("Error processing last messages before shutdown", exception);
        } finally {
            stream.delete().doOnTerminate(() -> log.info("Redis Stream deleted")).subscribe();
        }
    }

    private RStreamReactive<String, M> initStream(StreamConfiguration config, RedissonReactiveClient redisson) {
        var streamName = config.getStreamName();
        var codec = config.getCodec();
        RStreamReactive<String, M> streamInstance = redisson.getStream(streamName, codec);
        log.info("{} consumer listening for events on stream '{}'", getSubscriberName(), streamName);
        enforceConsumerGroup(streamInstance);
        setupStreamListener(streamInstance);
        return streamInstance;
    }

    private void enforceConsumerGroup(RStreamReactive<String, M> stream) {
        // Make sure the stream and the consumer group exists
        var args = StreamCreateGroupArgs.name(config.getConsumerGroupName()).makeStream();
        stream.createGroup(args)
                .onErrorResume(err -> {
                    if (err.getMessage().contains(BUSYGROUP)) {
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
                .flatMap(entry -> processReceivedMessages(stream, entry)
                        .subscribeOn(Schedulers.boundedElastic()), batchSize) // Concurrency hint
                .onErrorContinue(
                        (throwable, object) -> log.error("Error processing message from Redis stream", throwable))
                .subscribe();
    }

    private Mono<Void> processReceivedMessages(
            RStreamReactive<String, M> stream, Map.Entry<StreamMessageId, Map<String, M>> entry) {

        var messageId = entry.getKey();
        long startProcessingTime = System.currentTimeMillis();

        var message = entry.getValue().get(payloadField);
        log.info("Message received with id '{}'", messageId);

        // Remove messages from Redis pending list

        return processEvent(message)
                .then(Mono.defer(() -> stream.ack(config.getConsumerGroupName(), messageId)
                        .then(stream.remove(messageId))
                        .doOnError(throwable -> log.error("Error acknowledging or removing message with id '{}'",
                                messageId,
                                throwable))
                        .doFinally(signalType -> {
                            long processingTime = System.currentTimeMillis() - startProcessingTime;
                            messageProcessingTime.record(processingTime);

                            var messageTimestamp = extractTimeFromMessageId(messageId);
                            messageTimestamp.ifPresent(msgTime -> {
                                var queueDelay = startProcessingTime - msgTime;
                                messageQueueDelay.record(queueDelay);
                            });
                        })
                        .then()));
    }

    /**
     * Provide a particular implementation for processing the event.
     * @param message a Redis message
     */
    protected abstract Mono<Void> processEvent(M message);

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
