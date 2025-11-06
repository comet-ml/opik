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
import org.redisson.api.AutoClaimResult;
import org.redisson.api.PendingEntry;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.options.PlainOptions;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamPendingRangeArgs;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * This is the Base Redis Subscriber, for all particular implementations to extend. It listens to a Redis stream.
 * Extending classes must provide a particular implementation for the processEvent method.
 * This class implements the Managed interface to be able to start and stop the stream connected to the
 * application lifecycle.
 */
public abstract class BaseRedisSubscriber<M> implements Managed {

    private static final String BUSYGROUP = "BUSYGROUP";

    /**
     * Non-retryable: programming and validation exceptions that won't succeed on retry.
     */
    private static final Set<Class<? extends RuntimeException>> NON_RETRYABLE_EXCEPTIONS = Set.of(
            ArithmeticException.class,
            ArrayIndexOutOfBoundsException.class,
            ClassCastException.class,
            IllegalArgumentException.class,
            IllegalStateException.class,
            IndexOutOfBoundsException.class,
            NullPointerException.class,
            NumberFormatException.class,
            UnsupportedOperationException.class);

    /**
     * Enough for 2-3 concurrent Redis operations (read, ack, remove etc.) per subscriber
     */
    private static final int CONSUMER_SCHEDULER_THREAD_CAP_SIZE = 4;

    /**
     * Small queue, backpressure handled upstream
     */
    private static final int CONSUMER_SCHEDULER_QUEUED_TASK_CAP = 100;

    /**
     * Same TTL as Reactor bounded elastic scheduler default, but inner constant is not exposed.
     */
    private static final int BOUNDED_ELASTIC_SCHEDULER_TTL_SECONDS = 60;

    /**
     * Logger for the actual subclass, in order to have the correct class name in the logs.
     */
    private final Logger log = LoggerFactory.getLogger(getClass());

    private final AtomicLong readCount = new AtomicLong();

    private final StreamConfiguration config;
    private final RedissonReactiveClient redisson;

    private final String payloadField;

    @Getter
    private final String consumerId;

    protected final Meter meter;
    private final LongHistogram messageProcessingTime;
    private final LongHistogram messageQueueDelay;
    private final LongCounter messageProcessingErrors;
    private final LongCounter backpressureDropCounter;
    private final LongCounter claimErrors;
    private final LongHistogram claimTime;
    private final DoubleGauge claimSize;
    private final LongCounter readErrors;
    private final LongHistogram readTime;
    private final DoubleGauge readSize;
    private final LongCounter ackAndRemoveErrors;
    private final LongHistogram ackAndRemoveProcessingTime;
    private final LongCounter listPendingErrors;
    private final LongHistogram listPendingTime;
    private final LongCounter unexpectedErrors;

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

        this.consumerId = "consumer-%s-%s".formatted(this.config.getConsumerGroupName(), UUID.randomUUID());

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
        this.backpressureDropCounter = meter
                .counterBuilder("%s_%s_backpressure_drops_total".formatted(metricNamespace, metricsBaseName))
                .setDescription("Total number of events dropped due to backpressure")
                .build();
        this.claimErrors = meter
                .counterBuilder("%s_%s_claim_errors".formatted(metricNamespace, metricsBaseName))
                .setDescription("Errors when auto claiming from Redis stream")
                .build();
        this.claimTime = meter
                .histogramBuilder("%s_%s_claim_time".formatted(metricNamespace, metricsBaseName))
                .setDescription("Time taken for Redis auto claim call")
                .setUnit("ms")
                .ofLongs()
                .build();
        this.claimSize = meter
                .gaugeBuilder("%s_%s_claim_size".formatted(metricNamespace, metricsBaseName))
                .setDescription("Number of pending messages claimed by Redis auto claim call")
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
                .setDescription("Number of messages returned by Redis group read calls")
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
        this.listPendingErrors = meter
                .counterBuilder("%s_%s_list_pending_errors".formatted(metricNamespace, metricsBaseName))
                .setDescription("Errors when listing pending messages from Redis stream")
                .build();
        this.listPendingTime = meter
                .histogramBuilder("%s_%s_list_pending_time".formatted(metricNamespace, metricsBaseName))
                .setDescription("Time taken for Redis list pending calls")
                .setUnit("ms")
                .ofLongs()
                .build();
        this.unexpectedErrors = meter
                .counterBuilder("%s_%s_unexpected_errors".formatted(metricNamespace, metricsBaseName))
                .setDescription("Unexpected errors caught")
                .build();
    }

    @Override
    public void start() {
        if (consumerScheduler == null) {
            consumerScheduler = Schedulers.newBoundedElastic(
                    CONSUMER_SCHEDULER_THREAD_CAP_SIZE,
                    CONSUMER_SCHEDULER_QUEUED_TASK_CAP,
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
                    config.getConsumerBatchSize() * 2, // Double the expected parallelism
                    config.getConsumerBatchSize() * 100, // Enqueue up to 100 tasks per expected parallelism
                    "redis-subscriber-workers-scheduler-%s-%s".formatted(
                            config.getConsumerGroupName(), config.getStreamName()),
                    BOUNDED_ELASTIC_SCHEDULER_TTL_SECONDS,
                    true);
        }
        if (streamSubscription == null) {
            streamSubscription = setupStreamListener();
        }
        log.info(
                "Consumer started successfully with configuration: streamName='{}', consumerGroupName='{}', consumerBatchSize='{}', poolingInterval='{}', longPollingDuration='{}', claimIntervalRatio='{}', pendingMessageDuration='{}', maxRetries='{}'",
                config.getStreamName(),
                config.getConsumerGroupName(),
                config.getConsumerBatchSize(),
                config.getPoolingInterval().toJavaDuration(),
                config.getLongPollingDuration().toJavaDuration(),
                config.getClaimIntervalRatio(),
                config.getPendingMessageDuration().toJavaDuration(),
                config.getMaxRetries());
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
            log.info(
                    "Consumer stopped successfully, streamName='{}', consumerGroupName='{}'",
                    config.getStreamName(),
                    config.getConsumerGroupName());
        }
        if (consumerScheduler != null && !consumerScheduler.isDisposed()) {
            consumerScheduler.dispose();
        }
    }

    /**
     * Removes the consumer from the Redis consumer group during stop and does not propagate errors.
     */
    private void removeConsumer() {
        try {
            stream.removeConsumer(config.getConsumerGroupName(), consumerId)
                    .subscribeOn(consumerScheduler)
                    .doOnSuccess(pendingMessages -> log.info(
                            "Removed consumer '{}', from group '{}', pendingMessages '{}'",
                            consumerId, config.getConsumerGroupName(), pendingMessages))
                    .onErrorResume(throwable -> {
                        log.warn("Failed to remove consumer '{}', group '{}'",
                                consumerId, config.getConsumerGroupName(), throwable);
                        return Mono.empty();
                    })
                    .block(config.getLongPollingDuration().toJavaDuration());
        } catch (RuntimeException exception) {
            log.warn("Exception while removing consumer '{}' from group '{}'",
                    consumerId, config.getConsumerGroupName(), exception);
        }
    }

    /**
     * Ensures the stream and consumer group exist.
     * Propagates errors except BUSYGROUP and makes start fail in that case.
     */
    private void enforceConsumerGroup() {
        var streamCreateGroupArgs = StreamCreateGroupArgs
                .name(config.getConsumerGroupName())
                .makeStream();
        stream.createGroup(streamCreateGroupArgs)
                .subscribeOn(consumerScheduler)
                .onErrorResume(throwable -> {
                    if (Objects.toString(throwable.getMessage(), "").contains(BUSYGROUP)) {
                        log.info("Consumer group already exists, name '{}', stream '{}'",
                                config.getConsumerGroupName(), config.getStreamName());
                        return Mono.empty();
                    }
                    log.error("Failed to create consumer group '{}' for stream '{}'",
                            config.getConsumerGroupName(), config.getStreamName(), throwable);
                    return Mono.error(throwable);
                })
                .block(config.getLongPollingDuration().toJavaDuration());
    }

    private Disposable setupStreamListener() {
        // The timerScheduler isolates interval
        return Flux.interval(config.getPoolingInterval().toJavaDuration(), timerScheduler)
                .onBackpressureDrop(i -> {
                    backpressureDropCounter.add(1);
                    // Backpressure should be a common thing due to long polling, better to log at debug level
                    log.debug(
                            "Backpressure drop detected: Unable to keep up with polling intervals. Polling interval tick dropped (sequence number: '{}').",
                            i);
                })
                // ConcatMap ensures one readGroup/autoClaim at a time
                .concatMap(i -> {
                    // Using our own counter to track real reads as ticks (i) might be dropped on backpressure
                    if (readCount.incrementAndGet() % config.getClaimIntervalRatio() == 0) {
                        return claimPendingMessages();
                    } else {
                        return readMessages();
                    }
                })
                .flatMapIterable(Map::entrySet)
                // Concurrency for processing messages
                .flatMap(this::processMessage, config.getConsumerBatchSize())
                // batch by time/size, then split outcomes
                .bufferTimeout(config.getConsumerBatchSize(), config.getPoolingInterval().toJavaDuration().dividedBy(3))
                .filter(CollectionUtils::isNotEmpty)
                .flatMap(this::postProcessSuccessMessages)
                .flatMap(this::postProcessFailureMessages)
                // Unexpected errors handling: interval is dropped, but processing continues
                .onErrorContinue((throwable, object) -> {
                    unexpectedErrors.add(1);
                    log.error("Unexpected error processing message from Redis stream '{}'", object, throwable);
                })
                .name("redis-subscriber")
                .tag("stream", config.getStreamName())
                .tag("consumer-group", config.getConsumerGroupName())
                .subscribe();
    }

    private Mono<Map<StreamMessageId, Map<String, M>>> claimPendingMessages() {
        var startMillis = System.currentTimeMillis();
        return stream.autoClaim(config.getConsumerGroupName(),
                consumerId,
                config.getPendingMessageDuration().toJavaDuration().toMillis(),
                TimeUnit.MILLISECONDS,
                StreamMessageId.MIN, // Start from the beginning of pending list
                config.getConsumerBatchSize())
                .subscribeOn(consumerScheduler)
                .filter(Objects::nonNull)
                .map(AutoClaimResult::getMessages)
                .filter(Objects::nonNull)
                .doOnSuccess(claimedMessages -> {
                    claimSize.set(claimedMessages.size());
                    log.debug("Successfully auto claimed from stream, size '{}'", claimedMessages.size());
                })
                .onErrorResume(throwable -> {
                    claimErrors.add(1);
                    log.error("Error claiming pending messages", throwable);
                    return Mono.just(Map.of());
                })
                .doFinally(signalType -> claimTime.record(System.currentTimeMillis() - startMillis));
    }

    private Mono<Map<StreamMessageId, Map<String, M>>> readMessages() {
        var startMillis = System.currentTimeMillis();
        var streamReadGroupArgs = StreamReadGroupArgs.neverDelivered()
                .count(config.getConsumerBatchSize())
                .timeout(config.getLongPollingDuration().toJavaDuration());
        return stream.readGroup(config.getConsumerGroupName(), consumerId, streamReadGroupArgs)
                .subscribeOn(consumerScheduler) // Isolates the Redis call
                .filter(Objects::nonNull)
                .doOnSuccess(messages -> {
                    readSize.set(messages.size());
                    log.debug("Successfully read from stream, size '{}'", messages.size());
                })
                .onErrorResume(throwable -> {
                    readErrors.add(1);
                    log.error("Error reading from Redis stream", throwable);
                    return Mono.just(Map.of());
                })
                .doFinally(signalType -> readTime.record(System.currentTimeMillis() - startMillis));
    }

    private Mono<ProcessingResult> processMessage(Map.Entry<StreamMessageId, Map<String, M>> entry) {
        var messageId = entry.getKey();
        log.info("Message received with messageId '{}'", messageId);
        var message = Optional.ofNullable(entry.getValue())
                .map(valueMap -> valueMap.get(payloadField))
                .orElse(null);
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
                // Make sure to always propagate downstream the original processing results
                .thenReturn(processingResults);
    }

    private Mono<List<ProcessingResult>> postProcessFailureMessages(List<ProcessingResult> processingResults) {
        var failures = processingResults.stream()
                .filter(processingResult -> processingResult.status() == MessageStatus.FAILURE)
                .toList();
        if (failures.isEmpty()) {
            return Mono.just(processingResults);
        }

        messageProcessingErrors.add(failures.size());

        // Separate non-retryable failures first as no need to query delivery count, ack and remove all
        var nonRetryable = failures.stream()
                .filter(failure -> !isRetryableException(failure.error()))
                .map(failure -> {
                    log.error("Non-retryable error for messageId '{}', removing from stream",
                            failure.messageId(), failure.error());
                    return failure.messageId();
                })
                .toList();

        // Separate retryable, query delivery count, ack and remove all that reached max retries, retry if max not reached
        return Flux.fromIterable(failures)
                .filter(failure -> isRetryableException(failure.error()))
                .flatMap(failure -> getDeliveryCount(failure.messageId())
                        .map(deliveryCount -> failure.toBuilder()
                                .deliveryCount(deliveryCount)
                                .build()),
                        CONSUMER_SCHEDULER_THREAD_CAP_SIZE) // Parallelism for delivery count Redis queries
                .filter(this::maxRetriesReached)
                .map(this::handleMaxRetriesReached)
                .collectList() // Emits an empty list if the sequence is empty
                .flatMap(maxRetries ->
                // Delete all non-retryable combined with max retries reached
                ackAndRemoveMessages(Stream.concat(nonRetryable.stream(), maxRetries.stream()).toList()))
                .thenReturn(processingResults);
    }

    private boolean maxRetriesReached(ProcessingResult failure) {
        // Filter out if max limit not reached, these are claimed and retried, so no ack and remove
        if (failure.deliveryCount() < config.getMaxRetries()) {
            log.warn("Retryable error for messageId '{}', deliveryCount '{}', will retry",
                    failure.messageId(), failure.deliveryCount(), failure.error());
            return false;
        }
        // Max retries reached, will ack and remove
        return true;
    }

    private StreamMessageId handleMaxRetriesReached(ProcessingResult maxRetriesFailure) {
        // TODO: Send to the dead letter queue (DLQ) for further analysis
        log.error("Max retries reached for messageId '{}', removing from stream",
                maxRetriesFailure.messageId(), maxRetriesFailure.error());
        return maxRetriesFailure.messageId();
    }

    private Mono<Long> ackAndRemoveMessages(List<StreamMessageId> messageIds) {
        if (CollectionUtils.isEmpty(messageIds)) {
            return Mono.just(0L);
        }
        var idsArray = messageIds.toArray(StreamMessageId[]::new);
        var startMillis = System.currentTimeMillis();
        return stream.ack(config.getConsumerGroupName(), idsArray)
                .subscribeOn(consumerScheduler)
                // Only attempt to remove if ack was successful
                .then(stream.remove(idsArray)
                        .subscribeOn(consumerScheduler))
                .doOnSuccess(size -> log.debug("Successfully ack and remove from stream, size '{}'", size))
                .onErrorResume(throwable -> {
                    // If ack and or remove fails, message will be automatically claimed and retried
                    ackAndRemoveErrors.add(1);
                    log.error("Error acknowledging or removing from Redis stream, size '{}'",
                            idsArray.length, throwable);
                    return Mono.just(0L);
                })
                .doFinally(sig -> ackAndRemoveProcessingTime.record(System.currentTimeMillis() - startMillis));
    }

    /**
     * Provide a particular implementation for processing the event.
     * <p>
     * Exception handling: Exceptions in {@link #NON_RETRYABLE_EXCEPTIONS} are immediately removed from the stream.
     * All other exceptions are retried up to {@code maxRetries} times before being removed.
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

    /**
     * Non-retryable exceptions are programming errors or validation failures that won't succeed on retry.
     * All other exceptions are considered retryable (transient errors like network issues, timeouts, etc.)
     * Unknown exceptions default to retryable for safety.
     */
    private boolean isRetryableException(Throwable exception) {
        return !NON_RETRYABLE_EXCEPTIONS.contains(exception.getClass());
    }

    /**
     * The delivery count indicates how many times a message has been delivered to consumers.
     * Uses {@link PendingEntry#getLastTimeDelivered()} which returns number of times that a given message was delivered.
     *
     * @param messageId the message ID to query
     * @return Mono with the delivery count (0 if not found or on error)
     */
    private Mono<Long> getDeliveryCount(StreamMessageId messageId) {
        return listPending(messageId)
                .map(PendingEntry::getLastTimeDelivered)
                .defaultIfEmpty(0L);
    }

    /**
     * Not a batch operation as only range queries are supported by the pending Redis API.
     * The number of messages in between the range can't be predicted, so the mandatory count arg can't be set in advance.
     * The alternative is iterating in batches, but could lead to very long waits if many messages are found within the range.
     * With all that in mind, queries per single message ID are a better approach.
     */
    private Mono<PendingEntry> listPending(StreamMessageId messageId) {
        var startMillis = System.currentTimeMillis();
        var streamPendingRangeArgs = StreamPendingRangeArgs.groupName(config.getConsumerGroupName())
                .startId(messageId)
                .endId(messageId)
                .count(1); // Supporting only listing by single message ID
        return stream.listPending(streamPendingRangeArgs)
                .subscribeOn(consumerScheduler)
                .filter(CollectionUtils::isNotEmpty)
                .map(List::getFirst) // Count is 1, so there would be only the first one
                .doOnSuccess(size -> log.debug("Successfully list pending messageId '{}'", messageId))
                .onErrorResume(throwable -> {
                    listPendingErrors.add(1);
                    log.warn("Error listing pending messageId '{}'", messageId, throwable);
                    return Mono.empty();
                })
                .doFinally(signalType -> listPendingTime.record(System.currentTimeMillis() - startMillis));
    }

    @Builder(toBuilder = true)
    private record ProcessingResult(
            StreamMessageId messageId, MessageStatus status, Throwable error, long deliveryCount) {
    }

    private enum MessageStatus {
        SUCCESS,
        FAILURE,
    }
}
