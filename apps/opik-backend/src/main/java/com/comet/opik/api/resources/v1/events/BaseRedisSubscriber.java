package com.comet.opik.api.resources.v1.events;

import com.comet.opik.infrastructure.StreamConfiguration;
import io.dropwizard.lifecycle.Managed;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import org.apache.commons.collections4.CollectionUtils;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.options.PlainOptions;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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
     * Same TTL as Reactor bounded elastic scheduler default, but inner constant is not exposed.
     */
    private static final int BOUNDED_ELASTIC_SCHEDULER_TTL_SECONDS = 60;

    /**
     * Logger for the actual subclass, in order to have the correct class name in the logs.
     */
    private final Logger log = LoggerFactory.getLogger(getClass());

    private final StreamConfiguration config;
    private final RedissonReactiveClient redisson;

    private final String payloadField;

    @Getter
    private final String consumerId;

    protected final Meter meter;
    private final LongHistogram messageProcessingTime;
    private final LongHistogram messageQueueDelay;
    private final LongCounter messageProcessingErrors;
    private final LongCounter intervalDrops;
    private final LongCounter readErrors;
    private final LongHistogram readTime;
    private final DoubleGauge readSize;
    private final LongCounter ackAndRemoveErrors;
    private final LongHistogram ackAndRemoveProcessingTime;

    private volatile RStreamReactive<String, M> stream;
    private volatile Disposable streamSubscription;
    private volatile Scheduler timerScheduler;
    private volatile Scheduler consumerScheduler;
    private volatile Scheduler workersScheduler;

    protected BaseRedisSubscriber(
            @NonNull StreamConfiguration config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull String payloadField,
            @NonNull String metricNamespace,
            @NonNull String metricsBaseName) {
        this.config = config;
        this.redisson = redisson;

        this.payloadField = payloadField;

        this.consumerId = "consumer-%s-%s".formatted(config.getConsumerGroupName(), UUID.randomUUID());

        this.meter = GlobalOpenTelemetry.getMeter(metricNamespace);
        this.messageProcessingTime = meter
                .histogramBuilder("%s_%s_processing_time".formatted(metricNamespace, metricsBaseName))
                .setDescription("Time taken to process a message")
                .setUnit("ms")
                .ofLongs()
                .build();
        this.messageQueueDelay = meter
                .histogramBuilder("%s_%s_queue_delay".formatted(metricNamespace, metricsBaseName))
                .setDescription("Delay between message insertion in Redis and processing end")
                .setUnit("ms")
                .ofLongs()
                .build();
        this.messageProcessingErrors = meter
                .counterBuilder("%s_%s_processing_errors".formatted(metricNamespace, metricsBaseName))
                .setDescription("Errors when processing messages")
                .build();
        this.intervalDrops = meter
                .counterBuilder("%s_%s_interval_dropped".formatted(metricNamespace, metricsBaseName))
                .setDescription("Intervals dropped due to backpressure")
                .build();
        this.readErrors = meter
                .counterBuilder("%s_%s_read_errors".formatted(metricNamespace, metricsBaseName))
                .setDescription("Errors when reading from Redis stream")
                .build();
        this.readTime = meter
                .histogramBuilder("%s_%s_read_time".formatted(metricNamespace, metricsBaseName))
                .setDescription("Time taken for Redis read group calls")
                .setUnit("ms")
                .ofLongs()
                .build();
        this.readSize = meter
                .gaugeBuilder("%s_%s_read_size".formatted(metricNamespace, metricsBaseName))
                .setDescription("Number of messages returned for Redis group read calls")
                .build();
        this.ackAndRemoveErrors = meter
                .counterBuilder("%s_%s_ack_and_remove_errors".formatted(metricNamespace, metricsBaseName))
                .setDescription("Errors when acknowledging and removing from Redis stream")
                .build();
        this.ackAndRemoveProcessingTime = meter
                .histogramBuilder("%s_%s_ack_and_remove_time".formatted(metricNamespace, metricsBaseName))
                .setDescription("Time taken for Redis ack and remove calls")
                .setUnit("ms")
                .ofLongs()
                .build();
    }

    @Override
    public void start() {
        if (consumerScheduler == null) {
            consumerScheduler = Schedulers.newBoundedElastic(
                    Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE,
                    Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
                    "redis-subscriber-consumer-scheduler-%s-%s".formatted(
                            config.getConsumerGroupName(), config.getStreamName()),
                    BOUNDED_ELASTIC_SCHEDULER_TTL_SECONDS,
                    true);
        }
        if (stream == null) {
            // This particular subscriber implementation only consumes the respective Redis stream
            var plainOptions = PlainOptions.name(config.getStreamName())
                    // TODO investigate timeout, retryAttempts and retryDelay configuration here or at Redisson client level
                    .codec(config.getCodec());
            stream = redisson.getStream(plainOptions);
            enforceConsumerGroup();
        }
        if (timerScheduler == null) {
            timerScheduler = Schedulers.newSingle(
                    "redis-subscriber-timer-scheduler-%s-%s".formatted(
                            config.getConsumerGroupName(), config.getStreamName()),
                    true);
        }
        if (workersScheduler == null) {
            workersScheduler = Schedulers.newBoundedElastic(
                    Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE,
                    Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
                    "redis-subscriber-workers-scheduler-%s-%s".formatted(
                            config.getConsumerGroupName(), config.getStreamName()),
                    BOUNDED_ELASTIC_SCHEDULER_TTL_SECONDS,
                    true);
        }
        if (streamSubscription == null) {
            streamSubscription = setupStreamListener();
        }
        log.info(
                "Consumer started successfully with configuration: streamName='{}', consumerGroupName='{}', consumerBatchSize='{}', poolingInterval='{}', longPollingDuration='{}'",
                config.getStreamName(),
                config.getConsumerGroupName(),
                config.getConsumerBatchSize(),
                config.getPoolingInterval().toJavaDuration(),
                config.getLongPollingDuration().toJavaDuration());
    }

    @Override
    public void stop() {
        if (streamSubscription != null && !streamSubscription.isDisposed()) {
            streamSubscription.dispose();
        }
        if (timerScheduler != null && !timerScheduler.isDisposed()) {
            timerScheduler.dispose();
        }
        if (workersScheduler != null && !workersScheduler.isDisposed()) {
            workersScheduler.dispose();
        }
        if (stream != null && consumerScheduler != null && !consumerScheduler.isDisposed()) {
            removeConsumer();
            log.info("Consumer stopped successfully");
        }
        if (consumerScheduler != null && !consumerScheduler.isDisposed()) {
            consumerScheduler.dispose();
        }
    }

    private void removeConsumer() {
        log.info("Removing consumer '{}', from group '{}'", consumerId, config.getConsumerGroupName());
        try {
            stream.removeConsumer(config.getConsumerGroupName(), consumerId)
                    .subscribeOn(consumerScheduler)
                    .doOnSuccess(pendingMessages -> log.info(
                            "Removed consumer '{}', from group '{}', pendingMessages '{}'",
                            consumerId, config.getConsumerGroupName(), pendingMessages))
                    // TODO: investigate error handling here
                    // TODO: test subscribe vs block
                    .subscribe();
        } catch (Exception exception) {
            log.warn("Failed to remove consumer '{}', group '{}'",
                    consumerId, config.getConsumerGroupName(), exception);
        }
    }

    private void enforceConsumerGroup() {
        // Make sure the stream and the consumer group exists
        var streamCreateGroupArgs = StreamCreateGroupArgs
                .name(config.getConsumerGroupName())
                .makeStream();
        stream.createGroup(streamCreateGroupArgs)
                .subscribeOn(consumerScheduler)
                .onErrorResume(throwable -> {
                    if (throwable.getMessage().contains(BUSYGROUP)) {
                        log.info("Consumer group already exists, name '{}', stream '{}'",
                                config.getConsumerGroupName(), config.getStreamName());
                        return Mono.empty();
                    }
                    return Mono.error(throwable);
                })
                .subscribe();
    }

    private Disposable setupStreamListener() {
        // The timerScheduler isolates interval
        return Flux.interval(config.getPoolingInterval().toJavaDuration(), timerScheduler)
                .onBackpressureDrop(i -> {
                    intervalDrops.add(1);
                    //                    log.debug("Interval dropped due to backpressure");
                })
                // TODO: test publish on here
                // TODO: Implement claimer of orphan pending messages
                // ConcatMap ensures one readGroup at a time
                .concatMap(i -> readMessages())
                .flatMapIterable(Map::entrySet)
                // Concurrency for processing messages
                .flatMap(this::processMessage, config.getConsumerBatchSize())
                // batch by time/size, then split outcomes
                .bufferTimeout(config.getConsumerBatchSize(), config.getPoolingInterval().toJavaDuration().dividedBy(3))
                .filter(CollectionUtils::isNotEmpty)
                .flatMap(this::postProcessSuccessMessages)
                .flatMap(this::postProcessFailureMessages)
                // TODO: test error never stopping the interval
                .doOnError(throwable -> log.error("Error processing message from Redis stream", throwable))
                .name("redis-subscriber")
                .tag("stream", config.getStreamName())
                .tag("consumer-group", config.getConsumerGroupName())
                .subscribe();
    }

    private Mono<Map<StreamMessageId, Map<String, M>>> readMessages() {
        var startMillis = System.currentTimeMillis();
        var streamReadGroupArgs = StreamReadGroupArgs.neverDelivered()
                .count(config.getConsumerBatchSize())
                .timeout(config.getLongPollingDuration().toJavaDuration());
        return stream.readGroup(config.getConsumerGroupName(), consumerId, streamReadGroupArgs)
                .subscribeOn(consumerScheduler) // Isolates the Redis call
                .filter(Objects::nonNull)
                .doOnSuccess(stream -> {
                    readSize.set(stream.size());
                    log.debug("Successfully read from stream, size '{}'", stream.size());
                })
                .doOnError(throwable -> {
                    readErrors.add(1);
                    log.error("Error reading from Redis stream, size '{}'", config.getConsumerBatchSize(), throwable);
                })
                .doFinally(signalType -> readTime.record(System.currentTimeMillis() - startMillis));
    }

    private Mono<ProcessingResult> processMessage(Map.Entry<StreamMessageId, Map<String, M>> entry) {
        var messageId = entry.getKey();
        var message = Optional.ofNullable(entry.getValue())
                .map(valueMap -> valueMap.get(payloadField))
                .orElse(null);
        log.info("Message received with messageId '{}'", messageId);
        var startMillis = System.currentTimeMillis();
        // Deferring as processEvent is out of our control, it might not return a cold Mono
        return Mono.defer(() -> processEvent(message))
                .subscribeOn(workersScheduler)
                .thenReturn(ProcessingResult.builder()
                        .messageId(messageId)
                        .status(MessageStatus.SUCCESS)
                        .build())
                .doOnSuccess(r -> log.info("Successfully processed message messageId '{}'", entry.getKey()))
                .onErrorResume(throwable -> Mono.just(ProcessingResult.builder()
                        .messageId(messageId)
                        .status(MessageStatus.FAILURE)
                        .error(throwable)
                        .build()))
                .doFinally(signalType -> {
                    messageProcessingTime.record(System.currentTimeMillis() - startMillis);
                    extractTimeFromMessageId(messageId)
                            .ifPresent(messageMillis -> messageQueueDelay
                                    .record(System.currentTimeMillis() - messageMillis));
                });
    }

    private Mono<List<ProcessingResult>> postProcessSuccessMessages(List<ProcessingResult> processingResults) {
        var successIds = processingResults.stream()
                .filter(processingResult -> processingResult.status() == MessageStatus.SUCCESS)
                .map(ProcessingResult::messageId)
                .toList();
        return ackAndRemoveMessages(successIds)
                .thenReturn(processingResults);
    }

    private Mono<List<ProcessingResult>> postProcessFailureMessages(List<ProcessingResult> processingResults) {
        var failures = processingResults.stream()
                .filter(processingResult -> processingResult.status() == MessageStatus.FAILURE)
                .toList();
        // TODO: Implement proper error handling and dead letter queue
        // For now: log + metric only. No ack and remove
        messageProcessingErrors.add(failures.size());
        failures.forEach(failure -> log.error("Processing failed for message messageId '{}'",
                failure.messageId(), failure.error));
        return Mono.just(processingResults);
    }

    private Mono<Long> ackAndRemoveMessages(List<StreamMessageId> messageIds) {
        if (CollectionUtils.isEmpty(messageIds)) {
            return Mono.just(0L);
        }
        var idsArray = messageIds.toArray(StreamMessageId[]::new);
        var startMillis = System.currentTimeMillis();
        return stream.ack(config.getConsumerGroupName(), idsArray)
                .subscribeOn(consumerScheduler)
                .then(stream.remove(idsArray)
                        .subscribeOn(consumerScheduler))
                .doOnSuccess(size -> log.debug("Successfully ack and remove from stream, size '{}'", size))
                .doOnError(throwable -> {
                    ackAndRemoveErrors.add(1);
                    log.error("Error acknowledging or removing from Redis stream, size '{}'",
                            idsArray.length, throwable);
                })
                .doFinally(sig -> ackAndRemoveProcessingTime.record(System.currentTimeMillis() - startMillis));
    }

    /**
     * Provide a particular implementation for processing the event.
     *
     * @param message a Redis message
     */
    protected abstract Mono<Void> processEvent(M message);

    /**
     * Redis Streams messageId are in the format <millisecondsTime>-<sequenceNumber>
     *
     * @param messageId a Redis Stream messageId
     * @return a timestamp extracted from the messageId
     */
    private Optional<Long> extractTimeFromMessageId(StreamMessageId messageId) {
        try {
            var millisStr = messageId.toString().replaceAll("-\\d+", "");
            return Optional.of(Long.parseLong(millisStr));
        } catch (RuntimeException exception) {
            log.warn("Failed to parse timestamp from message ID: '{}'", messageId, exception);
        }
        return Optional.empty();
    }

    @Builder(toBuilder = true)
    private record ProcessingResult(StreamMessageId messageId, MessageStatus status, Throwable error) {
    }

    private enum MessageStatus {
        SUCCESS,
        FAILURE
    }
}
