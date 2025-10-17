package com.comet.opik.infrastructure.redis;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.queues.Job;
import com.comet.opik.infrastructure.queues.Queue;
import com.comet.opik.infrastructure.queues.QueueMessage;
import com.comet.opik.infrastructure.queues.QueueProducer;
import com.comet.opik.infrastructure.queues.RqJobUtils;
import com.comet.opik.infrastructure.queues.RqQueueConfig;
import io.dropwizard.util.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapReactive;
import org.redisson.api.RQueueReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * Publisher service for enqueueing jobs to Python RQ (Redis Queue) workers.
 *
 * This implementation creates jobs in RQ's native format:
 * - Jobs stored as Redis HASHes with RQ-specific fields
 * - 'data' field contains plain JSON bytes (UTF-8)
 * - Compatible with RQ workers using JSONSerializer/HybridSerializer
 * - No Python bridge needed
 */
@Slf4j
@RequiredArgsConstructor
class RqPublisher implements QueueProducer {

    public static final String RQ_QUEUE = "rq:queue:%s";
    public static final String RQ_JOB = "rq:job:%s";

    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull OpikConfiguration config;
    private final @NonNull IdGenerator idGenerator;

    /**
     * Enqueue a message to be processed by Python RQ worker.
     *
     * @param queue The queue to enqueue to
     * @param args The args to send [...]
     * @return Mono<String> The job ID
     */
    public Mono<String> enqueue(@NonNull Queue queue, @NonNull Object... args) {
        log.info("Enqueueing message to queue '{}'", queue);

        Job job = Job.builder()
                .func(queue.getFunctionName())
                .args(List.of(args))
                .kwargs(Map.of())
                .build();

        return enqueueJob(queue.toString(), job);
    }

    /**
     * Enqueue a job in RQ's native format.
     *
     * Creates a Redis HASH with RQ-specific fields:
     * - data: plain JSON bytes array [function, null, args, kwargs]
     * - created_at, enqueued_at: ISO-8601 timestamps
     * - status: 'queued'
     * - origin: queue name
     * - timeout: job timeout
     *
     * @param queueName The RQ queue name
     * @param job The RQ message (job data + metadata)
     * @return Mono<String> The job ID
     */
    public Mono<String> enqueueJob(@NonNull String queueName, @NonNull Job job) {

        String jobId = idGenerator.generateId().toString();

        // Use RQ's standard job key format
        String jobKey = RQ_JOB.formatted(jobId);

        log.info("Enqueueing RQ job '{}' to queue '{}' with function '{}'", jobId, queueName, job.func());

        // Get TTL from queue configuration
        Duration jobTimeToLive = config.getQueues().getQueue(queueName)
                .map(RqQueueConfig::getJobTTl)
                .orElse(config.getQueues().getDefaultJobTtl());

        // 2. Enhance with RQ metadata
        var message = QueueMessage.builder()
                .id(jobId)
                .origin(queueName)
                .timeoutInSec(jobTimeToLive.toSeconds())
                .build();

        // Get RQ queue list key used by Python RQ: 'rq:queue:' + queueName
        var rqQueueKey = RQ_QUEUE.formatted(queueName);
        RQueueReactive<String> rqQueue = redisClient.getQueue(rqQueueKey, StringCodec.INSTANCE);

        // Build RQ job HASH fields
        return Mono.fromCallable(() -> RqJobUtils.buildJobHash(message, job))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(jobFields -> {

                    RMapReactive<String, Object> jobMap = redisClient.getMap(jobKey, StringCodec.INSTANCE);

                    return jobMap.putAll(jobFields)
                            .then(jobMap.expire(jobTimeToLive.toJavaDuration()))
                            .then(rqQueue.offer(jobId)
                                    .doOnSuccess(ignored -> log.info("RQ job '{}' enqueued into '{}' (RQ list)", jobId,
                                            rqQueueKey))
                                    .doOnError(error -> log.error("Failed to enqueue RQ job '{}' into '{}'", jobId,
                                            rqQueueKey, error)))
                            .thenReturn(jobId);
                });
    }

    /**
     * Get the size of a queue.
     *
     * @param queueName The RQ queue name
     * @return Mono<Integer> The number of jobs in the queue
     */
    public Mono<Integer> getQueueSize(@NonNull String queueName) {
        String rqQueueKey = RQ_QUEUE.formatted(queueName);
        RQueueReactive<String> queue = redisClient.getQueue(rqQueueKey, StringCodec.INSTANCE);
        return queue.size();
    }
}
