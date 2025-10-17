package com.comet.opik.infrastructure.queues;

import reactor.core.publisher.Mono;

public interface QueueProducer {

    Mono<String> enqueue(Queue queue, Object... message);

    Mono<String> enqueueJob(String queueName, Job job);

    Mono<Integer> getQueueSize(String queueName);

}
