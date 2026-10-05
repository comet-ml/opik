package com.comet.opik.domain;

import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.comet.opik.infrastructure.redis.RedisStreamUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.redisson.api.RAtomicLongReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Singleton
@Slf4j
public class ExperimentItemPublisher {

    private final RedissonReactiveClient redisClient;
    private final ExperimentExecutionConfig config;
    private final TestSuiteAssertionCounterService testSuiteAssertionCounterService;
    private final ExperimentCancellationService cancellationService;

    @Inject
    public ExperimentItemPublisher(
            @NonNull RedissonReactiveClient redisClient,
            @NonNull @Config("experimentExecution") ExperimentExecutionConfig config,
            @NonNull TestSuiteAssertionCounterService testSuiteAssertionCounterService,
            @NonNull ExperimentCancellationService cancellationService) {
        this.redisClient = redisClient;
        this.config = config;
        this.testSuiteAssertionCounterService = testSuiteAssertionCounterService;
        this.cancellationService = cancellationService;
    }

    /**
     * Sets the batch counter atomically, then publishes all messages to the Redis stream.
     * The counter is set BEFORE publishing to prevent the race where a fast consumer
     * decrements to zero before all messages are published.
     */
    public Mono<Void> publish(@NonNull UUID batchId, List<ExperimentItemToProcess> messages, boolean testSuite) {
        if (CollectionUtils.isEmpty(messages)) {
            return Mono.empty();
        }

        var stream = redisClient.getStream(config.getStreamName(), config.getCodec());

        return setItemCounters(messages)
                .then(testSuite ? setAssertionCounters(messages) : Mono.empty())
                .thenMany(Flux.fromIterable(messages)
                        .flatMap(message -> stream.add(RedisStreamUtils.buildAddArgs(
                                ExperimentExecutionConfig.PAYLOAD_FIELD, message, config))
                                .doOnNext(id -> log.debug("Published experiment item message with ID: '{}'", id))
                                .map(id -> Map.entry(message.experimentId(), id))
                                .doOnError(throwable -> log.error("Error publishing experiment item message",
                                        throwable))))
                .collectMultimap(Map.Entry::getKey, Map.Entry::getValue)
                .flatMap(idsByExperiment -> cancellationService.recordQueued(
                        messages.getFirst().workspaceId(), idsByExperiment))
                .doOnSuccess(v -> log.info("Published '{}' experiment item messages for batch '{}'",
                        messages.size(), batchId));
    }

    /**
     * One counter per prompt variant, all set before the first message is published so a fast
     * consumer cannot drain a variant before its own counter exists.
     */
    private Mono<Void> setItemCounters(List<ExperimentItemToProcess> messages) {
        var itemsByExperiment = messages.stream()
                .collect(Collectors.groupingBy(ExperimentItemToProcess::experimentId, Collectors.counting()));

        return Flux.fromIterable(itemsByExperiment.entrySet())
                .flatMap(entry -> {
                    RAtomicLongReactive counter = redisClient
                            .getAtomicLong(ExperimentExecutionConfig.itemCounterKey(entry.getKey()));

                    return counter.set(entry.getValue())
                            .then(counter.expire(config.getBatchCounterTtl().toJavaDuration()));
                })
                .then();
    }

    private Mono<Void> setAssertionCounters(List<ExperimentItemToProcess> messages) {
        var workspaceId = messages.getFirst().workspaceId();
        var itemsByExperiment = messages.stream()
                .filter(m -> m.experimentId() != null)
                .collect(Collectors.groupingBy(ExperimentItemToProcess::experimentId, Collectors.counting()));

        return testSuiteAssertionCounterService.setCounters(workspaceId, itemsByExperiment);
    }
}
