package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.TraceThreadConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import io.dropwizard.jobs.annotations.Every;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

@Singleton
@Slf4j
@Every("5s")
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TraceThreadsClosingJob extends Job {

    private final @NonNull TraceThreadService traceThreadService;
    private final @NonNull LockService lockService;
    private final @NonNull OpikConfiguration opikConfiguration;
    private final @NonNull RedissonReactiveClient redisClient;

    @Override
    public void doJob(JobExecutionContext jobExecutionContext) {
        var lock = new Lock("job", TraceThreadsClosingJob.class.getSimpleName());
        var timeoutToMarkThreadAsInactive = opikConfiguration.getTraceThreadConfig()
                .getTimeoutToMarkThreadAsInactive().toJavaDuration(); // This is the timeout to mark threads as inactive
        int limit = 1000; // Limit to process in each job execution

        lockAndProcessJob(lock, timeoutToMarkThreadAsInactive, limit)
                .doOnError(error -> log.error("Error processing closing of trace threads", error))
                .doOnSuccess(unused -> log.info("Successfully started closing trace threads process"));
    }

    private Mono<Void> lockAndProcessJob(Lock lock, Duration timeoutToMarkThreadAsInactive, int limit) {
        return lockService.bestEffortLock(
                lock,
                Mono.defer(() -> enqueueInRedis(
                        traceThreadService
                                .getProjectsWithPendingClosureThreads(
                                        Instant.now().minus(timeoutToMarkThreadAsInactive),
                                        limit))),
                Mono.fromCallable(() -> {
                    log.info("Could not acquire lock for TraceThreadsClosingJob, skipping execution");
                    return null;
                }),
                Duration.ofSeconds(4), // Timeout to release the lock
                Duration.ofMillis(300)); // Timeout to acquiring the lock
    }

    private Mono<Void> enqueueInRedis(Flux<ProjectWithPendingClosureTraceThreads> flux) {
        TraceThreadConfig traceThreadConfig = opikConfiguration.getTraceThreadConfig();
        var stream = redisClient.getStream(traceThreadConfig.getStreamName(), traceThreadConfig.getCodec());

        return flux.flatMap(message -> stream
                .add(StreamAddArgs.entry(TraceThreadConfig.PAYLOAD_FIELD, message))
                //Todo: Block the stream to add repetedly the same message
                .doOnNext(id -> successLog(id, traceThreadConfig))
                .doOnError(this::errorLog))
                .doOnComplete(() -> log.info("All messages enqueued successfully in stream {}",
                        traceThreadConfig.getStreamName()))
                .then();
    }

    private void errorLog(Throwable throwable) {
        log.error("Error sending message", throwable);
    }

    private void successLog(StreamMessageId streamMessageId, TraceThreadConfig config) {
        log.debug("Successfully enqueued message with ID {} in stream {}", streamMessageId, config.getStreamName());
    }

}
